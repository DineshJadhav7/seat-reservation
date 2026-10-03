# Seat Reservation Service (Java 21 / Spring Boot 3 / PostgreSQL)

Assigned-seat booking API that stays correct under on-sale stampedes: no seat sold twice, per-user limits,
idempotent retries. Prometheus metrics, JSON logs with request ids, one-command burst test.

| | |
|---|---|
| **Live URL** | `<paste Render URL>` |
| **Health / readiness** | `<URL>/healthz` · `<URL>/readyz` |
| **Metrics (Prometheus)** | `<URL>/metrics` |
| **CI proof (20k stampede)** | `<link to the green GitHub Actions run>` |

## Run it (clean checkout)

```bash
docker compose up --build          # API on http://localhost:8000, Postgres included
```

Without Docker (JDK 21, Maven, any Postgres):
```bash
createdb seats
DATABASE_URL=postgresql://postgres:postgres@localhost:5432/seats mvn spring-boot:run
```
Tables are created automatically on start-up (retrying until the database is reachable).

Config (env vars): `DATABASE_URL` (postgres:// or jdbc: form), `ADMIN_TOKEN` (default `admin-secret`), `PORT` (8000),
`PER_USER_LIMIT` (4), `DB_POOL_MAX` (20).

## One-command burst

Needs only a JDK, no Maven and no libraries:
```bash
make burst BASE_URL=https://<your-app>.onrender.com ADMIN_TOKEN=<admin token>
# or
java scripts/Burst.java https://<your-app>.onrender.com --admin-token <token> [--requests 20000 --users 5000 --concurrency 500]
```
It creates a fresh show and runs: (1) 500 users on seat A12, (2) a 20k-request stampede with hot seats, multi-seat
requests and same-key retries, (3) per-user limit, (4) idempotency, (5) spoofed identity and cancel. It prints the outcome
distribution (confirmed / declined by reason / 5xx), checks the reconciliation invariants and that `/metrics` agrees with
the API. Exit code is 1 if any check fails.

No laptop? On GitHub: Actions tab, `burst-live`, Run workflow, paste the live URL and `ADMIN_TOKEN`. The stampede runs on
GitHub's servers and the results appear on the run's summary page. Dropped connections are retried with the same idempotency key, which is exactly
what the key is for.

## Verified on every push (GitHub Actions)

`.github/workflows/ci.yml` builds the jar, starts it against a real Postgres, waits for `/readyz`, runs the full 20,000-request
stampede, then starts a second copy pointing at a dead database and checks that `/healthz` is 200 while `/readyz` fails closed
(503). It also builds the Docker image exactly as Render does. The stampede output is shown on the run's summary page.
The same thing runs locally with `mvn -DskipTests package && make verify`.

## API

Auth: `Authorization: Bearer <token>`. For buyers the token *is* the user id (demo auth); `POST /shows` needs `ADMIN_TOKEN`.
Identity never comes from the request body: a spoofed `user_id` field is ignored.

| Method | Path | Notes |
|---|---|---|
| POST | `/shows` | admin. `{"name","seats":[],"price_paise","per_user_limit"?}` |
| GET | `/shows/{id}` | per-seat status + counts; `available + held + confirmed == total_seats` |
| POST | `/shows/{id}/reserve` | `{"seats":[...]}` + `Idempotency-Key` header (or `idempotency_key` in the body) |
| POST | `/reservations/{id}/cancel` | owner only, idempotent |
| GET | `/healthz` · `/readyz` · `/metrics` | liveness · readiness (checks the DB, 503 when down) · Prometheus |

Reserve outcomes: `201` confirmed (a retry also returns 201 with header `Idempotent-Replay: true`);
`409` `seat_taken` / `per_user_limit` / `idempotency_conflict`; `404` `unknown_seat` / `show_not_found`;
`400` missing key; `401` / `403` auth. Business declines are never 5xx. Multi-seat requests are all-or-nothing.
Money is integer paise everywhere.

```bash
curl -X POST $URL/shows -H "Authorization: Bearer $ADMIN" -H 'content-type: application/json' \
  -d '{"name":"friday-night","seats":["A1","A2","A3"],"price_paise":25000}'
curl -X POST $URL/shows/$ID/reserve -H "Authorization: Bearer alice" -H "Idempotency-Key: k1" \
  -H 'content-type: application/json' -d '{"seats":["A1"]}'
```

## Metrics (`/metrics`, served by Spring Boot Actuator)
- `reservations_confirmed_total`
- `reservations_declined_total{reason="seat_taken|per_user_limit|idempotent_replay|idempotency_conflict|unknown_seat"}`
- `seats_available{show_id}` and `seats{show_id,status}`: recomputed from the database right before each scrape, so they cannot drift
- plus the standard JVM, HikariCP (`hikaricp_connections_pending` ...) and `http_server_requests_seconds` metrics

## Logs
One JSON line per request with `requestId` (taken from `X-Request-Id` or generated, and echoed in the response), method,
path, status and duration. Reservation events add `reservation_id`, `user` and `seats`. On Render: dashboard, Logs tab.

## Deploy (Render)
1. Push this repo to GitHub.
2. Render, New, Blueprint, pick the repo. `render.yaml` creates the web service and a Postgres database, wires `DATABASE_URL`
   and generates `ADMIN_TOKEN`.
3. Copy `ADMIN_TOKEN` from the service's Environment tab. The health check path is `/readyz`.

The free tier sleeps when idle, so the first request after a pause is a cold start of a minute or so.

## Code map
```
config/DbUrl                 postgres://... -> jdbc:postgresql://... conversion
config/DatabaseConfig        Hikari pools: the main pool and a small separate one for /readyz and metrics
config/SchemaInitializer     creates the tables on start-up, retries until the DB is up
service/ReservationService   all booking rules, one transaction per request
web/*Controller              thin HTTP layer
web/RequestContextFilter     request id + access log
web/MetricsRefreshFilter     refreshes the seat gauges before /metrics is rendered
web/ApiExceptionHandler      business declines -> 4xx JSON, bugs -> 500
metrics/SeatMetrics          Micrometer counters and gauges
logging/*                    tiny JSON log layout (no extra dependency)
scripts/Burst.java           the stampede test
scripts/verify.sh            start, stampede, fail-closed readiness check (used by CI)
.github/workflows/         ci.yml (build + stampede + docker build) and burst-live.yml (stampede against a deployed URL)
```

Design notes: [WRITEUP.md](WRITEUP.md).
