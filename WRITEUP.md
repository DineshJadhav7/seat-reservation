# Write-up

## 1. The atomic decision
Every seat decision happens in **one Postgres transaction** and is made while holding a **row lock**:

```sql
SELECT label, status FROM seats
WHERE show_id = ? AND label = ANY(?) ORDER BY label FOR UPDATE
```
Only after those rows are locked do I read `status`. A second buyer for A12 blocks on that lock. When the winner commits, the
loser's query gets the row back as `confirmed` (READ COMMITTED re-reads a row that was changed while it waited) and it
gets a 409. "Is it free?" and "take it" cannot interleave, which is exactly what a read-then-write gets wrong.
The schema backs this up: `PRIMARY KEY (show_id, label)` and `CHECK ((status = 'available') = (user_id IS NULL))`, so a
seat can never be half-owned.

**Multi-seat = all-or-nothing.** Seats are trimmed, de-duplicated and sorted, then locked with one `ORDER BY label FOR UPDATE`
statement. If any seat is unavailable the transaction rolls back and nothing is booked (409 listing the taken seats).
Every reserve and cancel locks rows in the same `ORDER BY label` order, so `[A1,A2]` and `[A2,A1]` queue behind each other
instead of deadlocking.

**Per-user limit.** The transaction starts with `pg_advisory_xact_lock(hashtextextended('user:<id>', 0))`. That serialises one
user's requests (nobody else is blocked), so "count what they hold, then add" cannot race against the same user's parallel calls.
Because each statement in READ COMMITTED takes a fresh snapshot, the second request sees what the first one committed.
Ten parallel reserves on a limit-4 show give exactly 4 successes.

## 2. Idempotency
The key lives on the `reservations` row: `(user_id, idempotency_key, request_hash)` with `UNIQUE (user_id, idempotency_key)`.
The lookup runs inside the per-user advisory lock, so two concurrent requests with the same key run one after the other; the
second finds the first's row and returns it (`201`, header `Idempotent-Replay: true`, counted as `idempotent_replay`).
`request_hash` is SHA-256 of show + sorted seats; the same key with a different body is `409 idempotency_conflict`. Keys are
scoped per user. A declined attempt (seat taken, over limit) stores nothing, so the client may retry the same key later.
The unique constraint is the backstop if the application check ever had a bug.

## 3. Holds & expiry
I chose the **explicit cancel** model: `POST /reservations/{id}/cancel`, owner-only (403 otherwise) and idempotent. Cancel locks
the reservation row, then its seats (same sorted order), and frees only seats whose `reservation_id` equals this reservation, so a
cancel can never free a seat that was confirmed to somebody else. Reservations are confirmed immediately (there is no payment step),
so `held` exists in the schema and in the invariant but is always 0 today. With a payment step I would add `held` + `expires_at` and
a sweeper doing `UPDATE ... WHERE status='held' AND expires_at < now()`, guarded the same way.

## 4. Consistency vs availability
Postgres is the single source of truth and I chose **consistency**. If the database is unreachable the API answers 503 instead of
guessing, and `/readyz` fails closed so the platform stops routing traffic. I never accept a booking I cannot durably record.
The cost is that availability depends on the primary; the next step would be a synchronous replica with automatic failover, which
keeps the same guarantee. Clients can retry safely because of the idempotency key. `/readyz` and the metrics gauges use their
own small connection pool, so a saturated main pool during a burst cannot make the platform think the service is down.

## 5. Observability: what would page me at 2am
- **Any sustained 5xx.** Declines are 4xx, so a 5xx is a real bug.
- **`/readyz` failing** or the database unreachable.
- **p99 of `POST /shows/{id}/reserve`** far above baseline: lock contention or pool exhaustion (`hikaricp_connections_pending` above 0 for minutes).
- **Invariant drift:** `seats{status="available"} + held + confirmed != total`. Impossible by construction, but cheap to alert on.
- **Counter vs state:** `reservations_confirmed_total` rising while `seats_available` does not fall.
- Not paged: `seat_taken` spikes at on-sale are normal; a jump in `idempotency_conflict` suggests a client bug.
Every log line carries `requestId`, echoed in the `X-Request-Id` response header, so one complaint can be traced end to end.

## 6. AI usage (honest)
I used Claude (Anthropic) heavily on this assignment, as the brief allows.
- **What I directed:** the stack (Java 21, Spring Boot, PostgreSQL), the requirements from your brief, the deliverables (burst script,
  metrics, readiness, Docker, Render) and the rule that it has to be provable from a clean checkout.
- **What Claude proposed and drafted:** the concurrency design (sorted row locks, per-user advisory lock, idempotency key with request
  hash, owner-guarded cancel), the first version of all the code, the burst script, the CI workflow and these docs.
- **What I did with it:** I reviewed the generated implementation and the concurrency design, followed the SQL locking and
  idempotency flow to understand how the reservation rules are enforced, and ran the verification workflow from a clean checkout.
  I fixed a Spring bean-name collision in the request context filter when the initial CI run failed, then rebuilt and pushed the fix.
  I deployed the service to Render, verified the health and readiness endpoints, and ran the live burst verification with both 5,000
  and 20,000 requested operations. I reviewed the reconciliation, idempotency, per-user-limit, cancellation, and zero-5xx results
  before treating the live verification as complete.
- **Limits I know about:** demo auth (the token is the user id), no payment step so `held` is unused, and the free-tier database and
  CPU are small, so the 20k burst is slower there than on real hardware.

## 7. What I would do next
- Real auth (JWT) and per-user / per-IP rate limiting.
- A payment step with `held` + `expires_at`, a sweeper, and payment-provider idempotency keys.
- Integration tests with Testcontainers, plus unit tests for the pure helpers.
- PgBouncer and multiple replicas; run the 20k burst on proper hardware.
- Paginate `GET /shows/{id}` for big halls and keep per-show counters instead of scanning.
- Grafana dashboard and alert rules as code; OpenTelemetry tracing.

## How it was tested
- GitHub Actions run (build, 20,000-request stampede against Postgres, fail-closed readiness check): https://github.com/DineshJadhav7/seat-reservation/actions/runs/37111855506
- Stampede against the deployed service: `/readyz` returned 200; the 20,000-request live stampede completed with
  22,009 total requests including retries; zero 5xx/unrecoverable network errors; no seat sold twice; all reconciliation and
  metrics checks passed; final result: `RESULT: ALL CHECKS PASSED`.
