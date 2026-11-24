#!/usr/bin/env bash
# Resets staging data: drop + recreate the DB, run the Flyway pre-deploy step, re-seed,
# restart the API and frontend. Volumes for object storage and the media cache are kept.
#   scripts/reset.sh           full-scale seed
#   scripts/reset.sh --ci      tiny seed
#   scripts/reset.sh --empty   migrate only, no seed
source "$(dirname "$0")/lib.sh"
[[ "$DB_NAME" =~ ^wisenquire_staging ]] || { echo "Refusing: DB_NAME=$DB_NAME is not a staging database." >&2; exit 1; }
echo "== Stopping api + frontend"
compose stop api frontend
compose up -d mysql
wait_healthy mysql 240
echo "== Recreating database $DB_NAME"
compose exec -T -e MYSQL_PWD="$DB_ROOT_PASSWORD" mysql mysql -uroot -e \
  "DROP DATABASE IF EXISTS \`$DB_NAME\`; CREATE DATABASE \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; GRANT ALL PRIVILEGES ON \`$DB_NAME\`.* TO '$DB_USERNAME'@'%';"
echo "== Flyway (pre-deploy step)"
compose run --rm migrate
if [[ "${1:-}" != "--empty" ]]; then bash "$STAGING_DIR/scripts/seed.sh" "$@"; fi
echo "== Starting api + frontend"
compose up -d api frontend
wait_healthy api 420
wait_healthy frontend 120
echo "Reset done."
