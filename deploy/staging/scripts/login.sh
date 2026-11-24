#!/usr/bin/env bash
# Logs in through the public entry point (nginx) exactly like the browser does:
# password -> OTP challenge -> reads the OTP from Mailpit -> verify -> prints the JWT.
#   scripts/login.sh <email> [password]      (password defaults to SEED_USER_PASSWORD)
#   scripts/login.sh --admin                 (the platform super admin from .env)
# Staging only: it reads OTP emails from the local Mailpit.
source "$(dirname "$0")/lib.sh"
PY="$(command -v python || command -v python3)" || { echo "python is required" >&2; exit 1; }
BASE="http://127.0.0.1:${STAGING_HTTP_PORT:-8180}"

if [[ "${1:-}" == "--admin" ]]; then EMAIL="$SUPER_ADMIN_EMAIL"; PASS="$SUPER_ADMIN_PASSWORD"
else EMAIL="${1:?usage: login.sh <email> [password] | --admin}"; PASS="${2:-$SEED_USER_PASSWORD}"; fi

"$PY" "$STAGING_DIR/scripts/otp_login.py" --base "$BASE/api/v1" --mail "http://127.0.0.1:${STAGING_MAIL_UI_PORT:-8025}" \
  --origin "$BASE" --email "$EMAIL" --password "$PASS"
