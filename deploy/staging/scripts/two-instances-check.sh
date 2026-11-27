#!/usr/bin/env bash
# Step 5.22 (end of Phase 1): prove every scheduled job runs once per tick with two API instances (ShedLock).
#   scripts/two-instances-check.sh [seconds]      default 300; builds nothing (run scripts/up.sh first)
# Starts a second API (docker-compose.two-instances.yml, fast job intervals), counts jobs_runs_total on both
# instances over the window, and checks each job's total against what one lock allows. Exit 0 = pass.
# Evidence: target/staging-evidence/two-instances-<timestamp>.txt (backend repo). Returns staging to one instance.
source "$(dirname "$0")/lib.sh"
DURATION="${1:-300}"
TWO=(-f docker-compose.yml -f docker-compose.two-instances.yml)
MGMT1="http://127.0.0.1:${STAGING_MGMT_PORT:-19090}"
MGMT2="http://127.0.0.1:${STAGING_MGMT_PORT_2:-19091}"
OUT_DIR="$BACKEND_DIR/target/staging-evidence"; mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/two-instances-$(date +%Y%m%d-%H%M%S).txt"

# job lock-name  lockAtLeastFor-seconds (from the @SchedulerLock annotations)
JOBS=("ads-sync 60" "broadcast-scheduler 10" "broadcast-recovery 10" "whatsapp-receipt-reconcile 1" "whatsapp-receipt-cleanup 60")

ready() { curl -fsS "$1/actuator/health/readiness" >/dev/null 2>&1; }
runs() {  # runs <mgmt-url> <job>  -> integer count (0 if the job has not run yet)
  curl -fsS "$1/actuator/prometheus" | awk -v j="job=\"$2\"" 'index($0,"jobs_runs_total") == 1 && index($0, j) { split($NF, v, "."); s += v[1] } END { print s + 0 }'
}

restore() { echo "== Back to one instance"; docker compose -f docker-compose.yml up -d --remove-orphans >/dev/null; }
trap restore EXIT

echo "== Starting two API instances (fast job intervals)"
docker compose "${TWO[@]}" up -d
for url in "$MGMT1" "$MGMT2"; do
  for ((i = 0; i < 140; i++)); do ready "$url" && break; sleep 3; done
  ready "$url" || { echo "instance at $url not ready" >&2; exit 1; }
done
echo "== Both ready; measuring for ${DURATION}s"
declare -A A0 B0
for entry in "${JOBS[@]}"; do set -- $entry; A0[$1]=$(runs "$MGMT1" "$1"); B0[$1]=$(runs "$MGMT2" "$1"); done
sleep "$DURATION"

fail=0
{
  echo "Two-instance ShedLock check, $(date -Iseconds), window ${DURATION}s"
  printf '%-28s %8s %8s %8s %10s %s\n' job api api-2 total max-allowed result
  for entry in "${JOBS[@]}"; do
    set -- $entry; job=$1; least=$2
    a=$(( $(runs "$MGMT1" "$job") - A0[$job] )); b=$(( $(runs "$MGMT2" "$job") - B0[$job] ))
    total=$((a + b)); max=$(( DURATION / least + 1 ))
    if (( total >= 1 && total <= max )); then r=PASS; else r=FAIL; fail=1; fi
    printf '%-28s %8d %8d %8d %10d %s\n' "$job" "$a" "$b" "$total" "$max" "$r"
  done
  migrations=$(docker compose exec -T mysql mysql -u root -p"$DB_ROOT_PASSWORD" -N -e \
    "SELECT COUNT(*) FROM ${DB_NAME}.one_time_tasks WHERE task_key = 'whatsapp-credential-migration'" 2>/dev/null || echo "?")
  echo "whatsapp-credential-migration one_time_tasks rows: $migrations (expected 1: runs once across instances)"
  [[ "$migrations" == "1" ]] || fail=1
  echo "Without a working lock, each job's total would be about twice one instance's ticks (see the intervals in docker-compose.two-instances.yml)."
  (( fail == 0 )) && echo "RESULT: PASS" || echo "RESULT: FAIL"
} > "$OUT"
cat "$OUT"
echo "Evidence: $OUT"
exit $fail
