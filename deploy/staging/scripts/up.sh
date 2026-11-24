#!/usr/bin/env bash
# Build the backend JAR and both images, run the Flyway pre-deploy step, start staging.
#   scripts/up.sh             full build
#   scripts/up.sh --no-build  reuse existing images
source "$(dirname "$0")/lib.sh"
if [[ "${1:-}" != "--no-build" ]]; then
  echo "== Building backend JAR (saas-jar profile)"
  (cd "$BACKEND_DIR" && "$MVN" -B -q -DskipTests package -Psaas-jar -Dmaven.compiler.release=17)
  echo "== Building images"
  compose build
fi
echo "== Starting (migrate runs first; api waits for it)"
compose up -d
wait_healthy mysql 240
wait_healthy api 420
wait_healthy frontend 120
echo
echo "Staging is up:"
echo "  App (frontend + API)  http://127.0.0.1:${STAGING_HTTP_PORT:-8180}"
echo "  OTP emails (Mailpit)  http://127.0.0.1:${STAGING_MAIL_UI_PORT:-8025}"
echo "  Meta mock requests    http://127.0.0.1:${STAGING_META_MOCK_PORT:-8089}/__admin/requests"
echo "  Object storage (S3)   http://127.0.0.1:${STAGING_S3_PORT:-8333}  bucket ${S3_BUCKET}"
echo "  Health                http://127.0.0.1:${STAGING_MGMT_PORT:-19090}/actuator/health"
echo "Next: scripts/seed.sh (first time), then log in as a persona (RUNBOOK-staging.md)."
