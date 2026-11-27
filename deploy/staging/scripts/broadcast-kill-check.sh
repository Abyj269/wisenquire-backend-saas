#!/usr/bin/env bash
# Step 5.22 (end of Phase 1): kill the API in the middle of a broadcast and prove the recovery resumes it with
# no double sends (one wamid per recipient; one Meta send per number).
#   scripts/broadcast-kill-check.sh [recipients]     default 300; needs a seeded staging (scripts/seed.sh)
# Uses tenant t01, branch c1b1, flag broadcast.chunked_tx ON for t01, and a 300 ms delay on the Meta mock's send.
# The recovery resumes a SENDING broadcast after 5 minutes without a heartbeat, so the run takes about 8 minutes.
# Evidence: target/staging-evidence/broadcast-kill-<timestamp>.txt (backend repo). Exit 0 = PASS.
source "$(dirname "$0")/lib.sh"
N="${1:-300}"
PY="$(command -v python || command -v python3)"
BASE="http://127.0.0.1:${STAGING_HTTP_PORT:-8180}"
MOCK="http://127.0.0.1:${STAGING_META_MOCK_PORT:-8089}"
OUT_DIR="$BACKEND_DIR/target/staging-evidence"; mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/broadcast-kill-$(date +%Y%m%d-%H%M%S).txt"
sql() { compose exec -T -e MYSQL_PWD="$DB_ROOT_PASSWORD" mysql mysql -uroot -N -B "$DB_NAME" -e "$1" | tr -d '\r'; }

read -r ORG < <(sql "SELECT id FROM organizations WHERE code = 'STG-T01'")
read -r BRANCH COMPANY CONFIG < <(sql "SELECT b.id, b.company_id, w.id FROM branches b JOIN whatsapp_configurations w ON w.branch_id = b.id WHERE b.code = 'STG-T01-C1B1'")
[[ -n "${ORG:-}" && -n "${CONFIG:-}" ]] || { echo "Seeded tenant t01 not found: run scripts/seed.sh" >&2; exit 1; }

echo "== Flag broadcast.chunked_tx ON for t01; 300 ms Meta send delay"
sql "INSERT INTO feature_flag_overrides (flag_key, organization_id, enabled, set_by, reason) VALUES ('broadcast.chunked_tx', $ORG, 1, 'kill-check', 'Step 5.22')
     ON DUPLICATE KEY UPDATE enabled = 1"
MAPPING=$(curl -fsS -X POST "$MOCK/__admin/mappings" -H 'Content-Type: application/json' -d '{
  "priority": 0, "request": { "method": "POST", "urlPathPattern": "/v20.0/[^/]+/messages" },
  "response": { "status": 200, "fixedDelayMilliseconds": 300, "headers": { "Content-Type": "application/json" },
    "jsonBody": { "messaging_product": "whatsapp", "contacts": [ { "input": "mock", "wa_id": "mock" } ],
      "messages": [ { "id": "wamid.KILL.{{randomValue length=24 type='"'"'ALPHANUMERIC'"'"'}}" } ] },
    "transformers": ["response-template"] } }' | "$PY" -c 'import json,sys; print(json.load(sys.stdin)["id"])')
cleanup() {
  curl -fsS -X DELETE "$MOCK/__admin/mappings/$MAPPING" >/dev/null 2>&1 || true
  sql "DELETE FROM feature_flag_overrides WHERE flag_key = 'broadcast.chunked_tx' AND organization_id = $ORG" || true
}
trap cleanup EXIT
curl -fsS -X DELETE "$MOCK/__admin/requests" >/dev/null

NUMBERS=$("$PY" -c "import json; print(json.dumps(['9715%08d' % (20000000 + i) for i in range($N)]))")
TOKEN=$(bash "$STAGING_DIR/scripts/login.sh" "org.admin@t01.staging.test" | tail -1)
BODY=$("$PY" -c "import json,sys; print(json.dumps({'companyId': $COMPANY, 'branchId': $BRANCH, 'whatsappConfigurationId': $CONFIG,
  'name': 'Kill check $(date +%H%M%S)', 'templateName': 'hello_world', 'audienceType': 'CUSTOM_NUMBERS', 'customNumbers': json.loads(sys.argv[1])}))" "$NUMBERS")
ID=$(curl -fsS -X POST "$BASE/api/v1/broadcasts" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H "Origin: $BASE" -d "$BODY" \
  | "$PY" -c 'import json,sys; print(json.load(sys.stdin)["id"])')
echo "== Broadcast $ID with $N recipients; sending"
curl -fsS -X POST "$BASE/api/v1/broadcasts/$ID/send" -H "Authorization: Bearer $TOKEN" -H "Origin: $BASE" >/dev/null

for ((i = 0; i < 120; i++)); do
  SENT=$(sql "SELECT COUNT(*) FROM broadcast_recipients WHERE broadcast_id = $ID AND status = 'SENT'")
  (( SENT >= N / 3 )) && break; sleep 1
done
echo "== Killing the API (SIGKILL) at $SENT/$N sent"
compose kill api >/dev/null
AT_KILL=$(sql "SELECT status, COUNT(*) FROM broadcast_recipients WHERE broadcast_id = $ID GROUP BY status" | tr '\t\n' '= ')
compose up -d api >/dev/null
wait_healthy api 420
echo "== API back; waiting for the recovery (5 min without heartbeat, then the next recovery run)"
for ((i = 0; i < 160; i++)); do
  STATUS=$(sql "SELECT status FROM broadcasts WHERE id = $ID")
  [[ "$STATUS" == COMPLETED ]] && break; sleep 5
done

RECIPIENTS=$(sql "SELECT COUNT(*) FROM broadcast_recipients WHERE broadcast_id = $ID")
WITH_WAMID=$(sql "SELECT COUNT(*) FROM broadcast_recipients WHERE broadcast_id = $ID AND status = 'SENT' AND wamid IS NOT NULL")
UNCONFIRMED=$(sql "SELECT COUNT(*) FROM broadcast_recipients WHERE broadcast_id = $ID AND status = 'UNCONFIRMED'")
DISTINCT=$(sql "SELECT COUNT(DISTINCT wamid) FROM broadcast_recipients WHERE broadcast_id = $ID")
read -r SENDS DOUBLES < <(curl -fsS "$MOCK/__admin/requests?limit=100000" | "$PY" -c '
import json, sys, collections
reqs = [r["request"] for r in json.load(sys.stdin)["requests"] if r["request"]["method"] == "POST" and r["request"]["url"].endswith("/messages")]
to = collections.Counter(json.loads(r["body"]).get("to") for r in reqs)
print(sum(to.values()), sum(1 for v in to.values() if v > 1))')
SENDS="$(printf '%s' "$SENDS" | tr -d '\r')"; DOUBLES="$(printf '%s' "$DOUBLES" | tr -d '\r')"  # Python on Windows ends lines with CR
fail=0
{
  echo "Broadcast kill check, $(date -Iseconds), broadcast $ID, $N recipients (t01 c1b1, broadcast.chunked_tx ON)"
  echo "At the kill: $AT_KILL"
  echo "Final broadcast status: $STATUS"
  echo "Recipients: $RECIPIENTS; SENT with a wamid: $WITH_WAMID; distinct wamids: $DISTINCT; UNCONFIRMED (interrupted, not resent): $UNCONFIRMED"
  echo "Meta mock send requests: $SENDS; numbers sent more than once: $DOUBLES"
  # At most once (owner decision A): every recipient is SENT or UNCONFIRMED (at most the one in flight), never twice.
  [[ "$STATUS" == COMPLETED && $((WITH_WAMID + UNCONFIRMED)) == "$N" && "$DISTINCT" == "$WITH_WAMID" && "$UNCONFIRMED" -le 1 ]] || fail=1
  [[ "$DOUBLES" == 0 && "$SENDS" -le "$N" ]] || fail=1
  (( fail == 0 )) && echo "RESULT: PASS (resumed, zero double sends; $UNCONFIRMED interrupted message not resent)" || echo "RESULT: FAIL"
} > "$OUT"
cat "$OUT"; echo "Evidence: $OUT"
exit $fail
