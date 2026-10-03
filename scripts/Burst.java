import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * On-sale stampede against a running instance. No dependencies, needs JDK 11+ (21 recommended):
 *
 *   java scripts/Burst.java https://your-app.onrender.com --admin-token XXXX [--requests 20000] [--users 5000] [--concurrency 500]
 *
 * Phases: hot-seat storm -> full stampede (with same-key retries) -> per-user limit -> idempotency -> identity/cancel.
 * Prints the outcome distribution and checks the invariants. Exit code 1 if any check fails.
 */
public class Burst {

    record Resp(int status, String body, String replayHeader) {
    }

    static HttpClient http;
    static String base;
    static Semaphore permits;
    static final Map<String, Integer> outcomes = new ConcurrentHashMap<>();
    static final Map<String, List<String>> confirmedReservations = new ConcurrentHashMap<>(); // id -> seats
    static boolean allOk = true;

    public static void main(String[] args) throws Exception {
        // keep idle connections for less time than the server does, so we never reuse one it already closed
        System.setProperty("jdk.httpclient.keepalive.timeout", "3");
        base = args[0].replaceAll("/+$", "");
        String adminToken = "admin-secret";
        int requests = 20000, users = 5000, concurrency = 500;
        for (int i = 1; i < args.length - 1; i += 2) {
            switch (args[i]) {
                case "--admin-token" -> adminToken = args[i + 1];
                case "--requests" -> requests = Integer.parseInt(args[i + 1]);
                case "--users" -> users = Integer.parseInt(args[i + 1]);
                case "--concurrency" -> concurrency = Integer.parseInt(args[i + 1]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        permits = new Semaphore(concurrency);
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30)).build();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

        System.out.println("readyz -> " + get("/readyz").status());
        double confirmed0 = metric(get("/metrics").body(), "reservations_confirmed_total", null);

        List<String> mainSeats = new ArrayList<>();
        for (int r = 0; r < 19; r++) {
            for (int n = 1; n <= 100; n++) {
                mainSeats.add((char) ('A' + r) + "" + n); // A1..S100
            }
        }
        List<String> extraSeats = new ArrayList<>(); // reserved for the targeted phases 3-5
        for (int i = 1; i <= 30; i++) {
            extraSeats.add("Z" + i);
        }
        List<String> all = new ArrayList<>(mainSeats);
        all.addAll(extraSeats);

        Resp created = post("/shows", "{\"name\":\"burst-" + System.currentTimeMillis() + "\",\"seats\":" + jsonList(all)
                + ",\"price_paise\":25000}", Map.of("Authorization", "Bearer " + adminToken));
        if (created.status() != 201) {
            System.out.println("could not create show: " + created.status() + " " + created.body());
            System.exit(1);
        }
        String showId = field(created.body(), "id");
        System.out.println("created show " + showId + " with " + all.size() + " seats\n");

        // ---- 1) hot seat storm
        System.out.println("1) hot-seat storm: 500 users -> A12");
        long t0 = System.currentTimeMillis();
        List<Future<Resp>> futures = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            final int u = i;
            futures.add(pool.submit(() -> reserve("hot-" + u, showId, List.of("A12"), UUID.randomUUID().toString(), null)));
        }
        Map<Integer, Integer> codes = new TreeMap<>();
        for (Future<Resp> f : futures) {
            codes.merge(f.get().status(), 1, Integer::sum);
        }
        check("exactly one 201", codes.getOrDefault(201, 0) == 1, codes.toString());
        check("everyone else got 409", codes.getOrDefault(409, 0) == 499, "");
        System.out.println("  took " + (System.currentTimeMillis() - t0) / 1000.0 + "s");

        // ---- 2) stampede
        System.out.println("\n2) stampede: " + requests + " requests, " + users
                + " users, 60% on 10 hot seats, ~10% same-key retries");
        Random rnd = new Random();
        List<String> hot = mainSeats.subList(0, 10);
        List<Callable<Resp>> jobs = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            String user = "buyer-" + rnd.nextInt(users);
            int n = rnd.nextDouble() < 0.2 ? 2 : 1;
            List<String> source = rnd.nextDouble() < 0.6 ? hot : mainSeats;
            Set<String> picked = new LinkedHashSet<>();
            while (picked.size() < n) {
                picked.add(source.get(rnd.nextInt(source.size())));
            }
            List<String> seats = new ArrayList<>(picked);
            String key = UUID.randomUUID().toString();
            jobs.add(() -> reserve(user, showId, seats, key, null));
            if (rnd.nextDouble() < 0.10) {
                jobs.add(() -> reserve(user, showId, seats, key, null)); // client retry, same key
            }
        }
        Collections.shuffle(jobs);
        t0 = System.currentTimeMillis();
        List<Future<Resp>> stampede = new ArrayList<>();
        for (Callable<Resp> job : jobs) {
            stampede.add(pool.submit(job));
        }
        for (Future<Resp> f : stampede) {
            f.get();
        }
        System.out.println("  " + jobs.size() + " requests in " + (System.currentTimeMillis() - t0) / 1000.0 + "s");

        // ---- 3) per-user limit
        System.out.println("\n3) per-user limit: one user fires 10 parallel reserves (limit 4)");
        List<Future<Resp>> greedy = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            final String seat = "Z" + i;
            greedy.add(pool.submit(() -> reserve("greedy", showId, List.of(seat), UUID.randomUUID().toString(), null)));
        }
        int got = 0;
        for (Future<Resp> f : greedy) {
            if (f.get().status() == 201) {
                got++;
            }
        }
        check("exactly 4 confirmed, rest declined", got == 4, "(got " + got + ")");

        // ---- 4) idempotency
        System.out.println("\n4) idempotency");
        String k = UUID.randomUUID().toString();
        Resp r1 = reserve("idem-user", showId, List.of("Z11"), k, null);
        Resp r2 = reserve("idem-user", showId, List.of("Z11"), k, null);
        Resp r3 = reserve("idem-user", showId, List.of("Z12"), k, null);
        check("retry returns the same reservation",
                r1.status() == 201 && r2.status() == 201
                        && field(r1.body(), "reservation_id").equals(field(r2.body(), "reservation_id")), "");
        check("same key, different seats -> 409", r3.status() == 409, "");

        // ---- 5) identity + cancel
        System.out.println("\n5) identity + cancel");
        Resp a = reserve("alice", showId, List.of("Z13"), UUID.randomUUID().toString(), "\"user_id\":\"mallory\"");
        check("spoofed body user_id ignored", a.status() == 201 && "alice".equals(field(a.body(), "user_id")), "");
        String resId = field(a.body(), "reservation_id");
        check("non-owner cannot cancel",
                post("/reservations/" + resId + "/cancel", "", Map.of("Authorization", "Bearer mallory")).status() == 403, "");
        check("owner can cancel",
                post("/reservations/" + resId + "/cancel", "", Map.of("Authorization", "Bearer alice")).status() == 200, "");
        confirmedReservations.remove(resId);
        check("released seat is re-bookable",
                reserve("bob", showId, List.of("Z13"), UUID.randomUUID().toString(), null).status() == 201, "");

        // ---- results
        System.out.println("\n== outcome distribution ==");
        new TreeMap<>(outcomes).forEach((name, count) -> System.out.printf("  %-35s %d%n", name, count));
        check("zero 5xx and zero unrecoverable network errors",
                outcomes.getOrDefault("5xx", 0) == 0 && outcomes.getOrDefault("network_error", 0) == 0, "");

        System.out.println("\n== reconciliation ==");
        String state = get("/shows/" + showId).body();
        int total = intField(state, "total_seats"), available = intField(state, "available");
        int held = intField(state, "held"), confirmed = intField(state, "confirmed");
        System.out.println("  total=" + total + " available=" + available + " held=" + held + " confirmed=" + confirmed);
        check("available + held + confirmed == total", available + held + confirmed == total, "");
        List<String> sold = new ArrayList<>();
        confirmedReservations.values().forEach(sold::addAll);
        check("no seat sold twice", sold.size() == new HashSet<>(sold).size(), "");
        check("seats in 201 bodies == confirmed seats in show state", sold.size() == confirmed,
                "(" + sold.size() + " vs " + confirmed + ")");
        String metrics = get("/metrics").body();
        check("metrics: seats_available gauge == API", metric(metrics, "seats_available", showId) == available, "");
        double delta = metric(metrics, "reservations_confirmed_total", null) - confirmed0;
        check("metrics: confirmed counter == reservations created (incl. 1 cancelled)",
                delta == confirmedReservations.size() + 1, "(counter delta " + (long) delta + ")");

        System.out.println("\nRESULT: " + (allOk ? "ALL CHECKS PASSED" : "FAILURES ABOVE"));
        pool.shutdown();
        System.exit(allOk ? 0 : 1);
    }

    // ------------------------------------------------------------------ helpers

    static Resp reserve(String user, String showId, List<String> seats, String key, String extraJson) throws Exception {
        String body = "{\"seats\":" + jsonList(seats) + (extraJson == null ? "" : "," + extraJson) + "}";
        Resp r = null;
        for (int attempt = 1; attempt <= 3 && r == null; attempt++) {
            try {
                permits.acquire();
                try {
                    r = send(HttpRequest.newBuilder(URI.create(base + "/shows/" + showId + "/reserve"))
                            .timeout(Duration.ofSeconds(120))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + user)
                            .header("Idempotency-Key", key)
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build());
                } finally {
                    permits.release();
                }
            } catch (java.io.IOException e) {
                // A dropped connection. Retry with the SAME idempotency key: if the first attempt did reach the
                // server, the retry just returns the original reservation instead of booking a second time.
                outcomes.merge("retried_after_network_error", 1, Integer::sum);
                if (attempt == 3) {
                    outcomes.merge("network_error", 1, Integer::sum);
                    return new Resp(0, "", null);
                }
                Thread.sleep(100L * attempt);
            }
        }
        if (r.status() == 201) {
            boolean replay = "true".equals(r.replayHeader());
            outcomes.merge(replay ? "idempotent_replay" : "confirmed", 1, Integer::sum);
            confirmedReservations.put(field(r.body(), "reservation_id"), seatsOf(r.body()));
        } else if (r.status() >= 500) {
            outcomes.merge("5xx", 1, Integer::sum);
        } else {
            String err = field(r.body(), "error");
            outcomes.merge("declined:" + (err == null ? r.status() : err), 1, Integer::sum);
        }
        return r;
    }

    static Resp send(HttpRequest req) throws Exception {
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        return new Resp(res.statusCode(), res.body(), res.headers().firstValue("idempotent-replay").orElse(null));
    }

    static Resp get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(120)).GET().build());
    }

    static Resp post(String path, String json, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
        headers.forEach(b::header);
        return send(b.build());
    }

    static void check(String name, boolean cond, String info) {
        allOk &= cond;
        System.out.println("  [" + (cond ? "PASS" : "FAIL") + "] " + name + " " + info);
    }

    static String jsonList(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            sb.append(i == 0 ? "" : ",").append('"').append(items.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    /** Tiny JSON string-field extractor; good enough for this API's flat responses. */
    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static int intField(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    static List<String> seatsOf(String json) {
        Matcher m = Pattern.compile("\"seats\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
        List<String> out = new ArrayList<>();
        if (m.find()) {
            Matcher s = Pattern.compile("\"([^\"]+)\"").matcher(m.group(1));
            while (s.find()) {
                out.add(s.group(1));
            }
        }
        return out;
    }

    static double metric(String text, String name, String labelContains) {
        for (String line : text.split("\n")) {
            if (line.startsWith(name) && (labelContains == null || line.contains(labelContains))) {
                return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
            }
        }
        return 0;
    }
}
