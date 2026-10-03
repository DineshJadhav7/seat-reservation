#!/usr/bin/env bash
# End-to-end check of the built service. Used by CI and runnable locally:
#
#   mvn -B -DskipTests package
#   DATABASE_URL=postgresql://postgres:postgres@localhost:5432/seats bash scripts/verify.sh
#
# 1) starts the service   2) waits for /readyz   3) runs the full on-sale stampede (scripts/Burst.java)
# 4) starts a second copy pointing at a dead database and checks that /healthz is 200 but /readyz fails closed (503)
#
# Optional env: PORT (8000), ADMIN_TOKEN, REQUESTS (20000), APP_CMD (default: java -jar target/app.jar), LOG_DIR (.)
set -euo pipefail

PORT=${PORT:-8000}
ADMIN_TOKEN=${ADMIN_TOKEN:-ci-admin-token}
REQUESTS=${REQUESTS:-20000}
APP_CMD=${APP_CMD:-java -jar target/app.jar}
LOG_DIR=${LOG_DIR:-.}
export PORT ADMIN_TOKEN

pids=()
cleanup() {
  for pid in "${pids[@]:-}"; do
    kill "$pid" 2>/dev/null || true
  done
}
trap cleanup EXIT

wait_for() { # wait_for <url> <max seconds>
  for _ in $(seq 1 "$2"); do
    if curl -s -o /dev/null -f "$1"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

echo "== 1) start the service on port $PORT"
bash -c "exec $APP_CMD" > "$LOG_DIR/app.log" 2>&1 &
pids+=($!)
if ! wait_for "http://localhost:$PORT/readyz" 120; then
  echo "service did not become ready within 120s; last log lines:"
  tail -n 80 "$LOG_DIR/app.log"
  exit 1
fi
echo "ready"

echo
echo "== 2) full stampede ($REQUESTS requests)"
java scripts/Burst.java "http://localhost:$PORT" --admin-token "$ADMIN_TOKEN" --requests "$REQUESTS" | tee "$LOG_DIR/burst.txt"

echo
echo "== 3) readiness must fail closed when the database is unreachable"
NODB_PORT=$((PORT + 1))
PORT=$NODB_PORT DATABASE_URL="postgresql://postgres:postgres@localhost:1/seats" \
  bash -c "exec $APP_CMD" > "$LOG_DIR/app-nodb.log" 2>&1 &
pids+=($!)
if ! wait_for "http://localhost:$NODB_PORT/healthz" 120; then
  echo "liveness never came up for the no-database instance"
  tail -n 40 "$LOG_DIR/app-nodb.log"
  exit 1
fi
live=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$NODB_PORT/healthz")
ready=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$NODB_PORT/readyz")
echo "instance without a database: /healthz=$live /readyz=$ready (expected 200 and 503)"
if [ "$live" != "200" ] || [ "$ready" != "503" ]; then
  echo "FAIL: readiness does not fail closed"
  exit 1
fi

echo
echo "ALL VERIFICATION STEPS PASSED"
