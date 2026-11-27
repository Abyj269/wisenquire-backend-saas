#!/usr/bin/env bash
# Module 02 P2 (Step 6.3): the 2-tab login -> silent refresh -> logout browser test (Playwright, frontend repo).
#   scripts/e2e-auth.sh [email]          default org.admin@t01.staging.test; needs scripts/up.sh + scripts/seed.sh
# Turns flag auth.refresh_tokens ON for the user's org, restarts the API with a 20-second access token, rebuilds
# the frontend image from the frontend repo (E2E_SKIP_BUILD=1 to reuse it), runs the test, then restores both.
# One-time on this machine, in the frontend repo: npm install, then npx playwright install chromium.
source "$(dirname "$0")/lib.sh"
EMAIL="${1:-org.admin@t01.staging.test}"
ACCESS_MS="${E2E_ACCESS_MS:-20000}"
FRONTEND="$(cd "${FRONTEND_DIR:-$STAGING_DIR/../../../wse_leads_saas_frontend}" && pwd)"
BASE="http://127.0.0.1:${STAGING_HTTP_PORT:-8180}"
sql() { compose exec -T -e MYSQL_PWD="$DB_ROOT_PASSWORD" mysql mysql -uroot -N -B "$DB_NAME" -e "$1" | tr -d '\r'; }

read -r ORG < <(sql "SELECT organization_id FROM users WHERE email = '$EMAIL'")
[[ -n "${ORG:-}" && "$ORG" != "NULL" ]] || { echo "User $EMAIL not found or has no org: run scripts/seed.sh" >&2; exit 1; }

restore() {
  echo "== Restoring: flag off, default access-token lifetime"
  sql "DELETE FROM feature_flag_overrides WHERE flag_key = 'auth.refresh_tokens' AND organization_id = $ORG" || true
  compose up -d --no-deps api >/dev/null && wait_healthy api 420 || true
  # Recreating the API also removes the frontend that depends on it: start it again.
  compose up -d --no-deps frontend >/dev/null && wait_healthy frontend 120 || true
}
trap restore EXIT

echo "== Flag auth.refresh_tokens ON for org $ORG; API access token ${ACCESS_MS} ms"
sql "INSERT INTO feature_flag_overrides (flag_key, organization_id, enabled, set_by, reason) VALUES ('auth.refresh_tokens', $ORG, 1, 'e2e-auth', 'Step 6.3 e2e')
     ON DUPLICATE KEY UPDATE enabled = 1"
STAGING_ACCESS_TOKEN_MS="$ACCESS_MS" compose up -d --no-deps api
wait_healthy api 420
# Recreating the API also removes the frontend that depends on it, so the frontend is always started again.
if [[ "${E2E_SKIP_BUILD:-}" != "1" ]]; then
  echo "== Rebuilding the frontend image from $FRONTEND"
  compose up -d --build --no-deps frontend
else
  compose up -d --no-deps frontend
fi
wait_healthy frontend 120
# "running" is not "serving": wait until nginx answers the app and the API through it.
for ((i = 0; i < 60; i++)); do

  curl -fsS -o /dev/null "$BASE/login" && break; sleep 2
done

echo "== Playwright: 2 tabs"
(cd "$FRONTEND" && E2E_BASE="$BASE" E2E_MAIL="http://127.0.0.1:${STAGING_MAIL_UI_PORT:-8025}" E2E_EMAIL="$EMAIL" \
  E2E_PASSWORD="$SEED_USER_PASSWORD" E2E_ACCESS_MS="$ACCESS_MS" npx playwright test -c e2e/playwright.config.ts)
echo "PASS"
