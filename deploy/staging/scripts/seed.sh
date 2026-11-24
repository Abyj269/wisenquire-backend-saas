#!/usr/bin/env bash
# Loads the synthetic tenants into the staging DB (must be freshly migrated and empty).
#   scripts/seed.sh            full scale: 20 tenants, ~59.5k leads, ~238k messages
#   scripts/seed.sh --ci       tiny scale, same structure (used by CI)
# The generator is TEST-scope code (src/test/.../tools/seed): it never ships in the WAR/JAR.
# exec.args is split on spaces, so the personas path must stay relative (cwd = backend dir).
source "$(dirname "$0")/lib.sh"
SCALE=full; [[ "${1:-}" == "--ci" ]] && SCALE=ci
[[ "$DB_NAME" =~ ^wisenquire_(staging|ci) ]] || { echo "Refusing: DB_NAME=$DB_NAME is not a staging/ci database." >&2; exit 1; }
export SEED_TARGET=staging DB_HOST="${SEED_DB_HOST:-127.0.0.1}" DB_PORT="${SEED_DB_PORT:-${STAGING_DB_PORT:-3307}}"
export MAVEN_OPTS="-Duser.timezone=Asia/Dubai ${MAVEN_OPTS:-}"
echo "== Seeding scale=$SCALE into $DB_NAME at $DB_HOST:$DB_PORT"
cd "$BACKEND_DIR"
"$MVN" -B -q test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dmaven.compiler.release=17 \
  -Dexec.mainClass=com.leads.tools.seed.StagingSeed -Dexec.classpathScope=test \
  -Dexec.args="--scale=$SCALE --personas=deploy/staging/personas.csv"
