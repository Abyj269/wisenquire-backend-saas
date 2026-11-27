# Runbook: SaaS staging (docker-compose)

Plan Phase 0, Step 3.3; `docs/saas-modules/01-foundation.md` Part A item 4. Q5 (cloud) is not decided, so staging runs as a docker-compose stack on one machine. Everything listens on `127.0.0.1` only.

## What runs

| Service | Image | Role | Host port |
|---|---|---|---|
| `mysql` | `mysql:8.0` | Database, prod server settings (`lower_case_table_names=1`, utf8mb4, UTC) | 3307 (seed tool, inspection) |
| `migrate` | `wisenquire-backend:staging` | Flyway pre-deploy step (`MigrateMain`), then exits | — |
| `api` | `wisenquire-backend:staging` | The SaaS JAR, **prod profile unchanged** | 19090 (management: health, Prometheus) |
| `frontend` | `wisenquire-frontend:staging` | nginx: Angular `staging` build + same-origin proxy of `/api/v1` (REST, SockJS) to `api` | **8180** (the app) |
| `object-storage` | `chrislusf/seaweedfs:3.80` | S3-compatible storage, bucket from `.env` (`S3_BUCKET`). Not used by code yet | 8333 |
| `object-storage-init` | `amazon/aws-cli` | Creates the bucket, then exits | — |
| `meta-mock` | `wiremock/wiremock:3.9.1` | Meta Graph API mock; every seeded WhatsApp config talks to it | 8089 (`/__admin/requests`) |
| `mailpit` | `axllent/mailpit:v1.21` | Catches all email, including login OTPs | 8025 (web UI) |

**Google Maps (optional):** set `GOOGLE_MAPS_API_KEY` in `.env` to a staging-only browser key restricted to `http://127.0.0.1:8180/*`, then `docker compose up -d frontend`. Empty = the delivery location picker is off. The frontend serves it as `/assets/runtime-config.json` (see `wse_leads_saas_frontend/docs/RUNTIME-CONFIG.md`).

Ports are overridable in `.env` (`STAGING_HTTP_PORT`, `STAGING_DB_PORT`, `STAGING_MAIL_UI_PORT`, ...). The default app port is 8180 because 8080 is often taken by a local Tomcat/XAMPP.

**MinIO** was planned for object storage, but MinIO no longer publishes container images (`minio/minio` and `minio/mc` are gone from Docker Hub and Quay), so staging uses SeaweedFS. Only S3 compatibility matters; the bucket is created with the standard AWS CLI.

## Prerequisites
- Docker Desktop running, with about 4 GB of memory for the stack.
- Git Bash (the scripts are bash), Python 3 on PATH (`login.sh`).
- Maven at `D:\maven\apache-maven-3.9.16` (or set `MVN=/path/to/mvn`), JDK 17.
- The frontend repo checked out next to the backend (`../wse_leads_saas_frontend`), or set `FRONTEND_DIR` in `.env`.
- Run every command from `wse_leads_saas_backend/deploy/staging` in Git Bash.

## First deploy
```bash
bash scripts/init-env.sh     # once: random staging-only secrets in .env (git-ignored)
bash scripts/up.sh           # builds the JAR + both images, migrates, starts, waits for health
bash scripts/seed.sh         # 20 synthetic tenants (about 1-2 minutes)
```
`up.sh --no-build` restarts with the existing images.

## Deploy a new version
```bash
git pull                     # backend and frontend repos
bash scripts/up.sh           # rebuilds; migrate runs the new migrations before the api starts
```
Data is kept. If the new version needs fresh seed data, run `scripts/reset.sh` (see RUNBOOK-seed-reset.md).

## Log in
1. Open http://127.0.0.1:8180.
2. Email: any persona from `personas.csv` (written by the seed, next to this file). Password: `SEED_USER_PASSWORD` in `.env`.
3. The OTP arrives in Mailpit: http://127.0.0.1:8025.

Persona emails follow one pattern (tenant `tNN`, company `cN`, branch `cNbM`):

| Persona | Email | Scope |
|---|---|---|
| ORG_ADMIN | `org.admin@t01.staging.test` | the whole tenant |
| COMPANY_MGR | `company.mgr.c1@t01.staging.test` | company c1 |
| BRANCH_MGR | `branch.mgr.c1b1@t01.staging.test` | branch c1b1 |
| AGENT_OWN_1 / _2 | `agent1.c1b1@t01.staging.test`, `agent2.c1b1@...` | branch c1b1 (OWN scope arrives with D14) |

The platform super admin is `SUPER_ADMIN_EMAIL` / `SUPER_ADMIN_PASSWORD` in `.env`.

From the command line (prints a JWT): `bash scripts/login.sh agent1.c1b1@t01.staging.test` or `bash scripts/login.sh --admin`. The OTP issue rate limit is one per user per minute.

## Checks after a deploy
```bash
curl -fsS http://127.0.0.1:19090/actuator/health/readiness          # {"status":"UP"...}
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8180/api/v1/leads             # 401
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8180/api/v1/actuator/health   # 404 (blocked at nginx)
T=$(bash scripts/login.sh branch.mgr.c1b1@t01.staging.test) && echo ok
```
Meta calls the app made: http://127.0.0.1:8089/__admin/requests.

## Logs, stop, wipe
```bash
docker compose logs -f api           # or frontend, migrate, meta-mock, ...
bash scripts/down.sh                 # stop, keep data
bash scripts/down.sh --wipe          # stop and delete all volumes (DB, object storage, media cache)
```

## Troubleshooting
| Symptom | Cause / fix |
|---|---|
| `ports are not available ... 127.0.0.1:8180` | Something else uses the port: set `STAGING_HTTP_PORT` in `.env` |
| Every POST through the browser returns 403 `Invalid CORS request` | The proxy sent a port that differs from the browser's. nginx must not send `X-Forwarded-Port: $server_port` (see `docker/nginx.conf.template` in the frontend repo) |
| API container exits at start with `Production requires ...` | `.env` is missing or was edited: `ProductionSafetyConfiguration` checks the secrets. Re-run `init-env.sh --force`, then `down.sh --wipe` (the DB password changed) |
| `migrate` fails | `docker compose logs migrate`; a migration error blocks the API on purpose |
| No OTP email | `docker compose logs api` for SMTP errors; Mailpit must be running |
| Seed refuses: `organizations is not empty` | Seed only a fresh DB: `scripts/reset.sh` |

## Moving to a cloud (when Q5 is decided)
Each service maps to a managed one; the app needs no code change, only environment variables:

| Here | Cloud |
|---|---|
| `mysql` | Managed MySQL 8 (same server settings; `lower_case_table_names=1` must be set at creation) |
| `migrate` | A one-off job / pre-deploy hook with the same image and entrypoint |
| `api` | Container service (≥ 1 instance; ShedLock arrives in Part B before scaling out) |
| `frontend` | The same nginx image, or static hosting + a path-based load-balancer rule for `/api/v1` |
| `object-storage` | S3 / GCS / Azure Blob |
| `.env` | The cloud's secrets store |
| `mailpit` | A real SMTP provider (staging sender domain) |
| `meta-mock` | Keep it: staging must never call Meta with real credentials |
Terminate TLS at the load balancer and set `ALLOWED_ORIGINS` to the real staging origin.

## Two-instance job check (Step 5.22)
- Purpose: prove every scheduled job runs once per tick with two API instances (ShedLock).
- Run (Git Bash, staging up from the current code via `scripts/up.sh`): `bash scripts/two-instances-check.sh [seconds]` (default 300).
- What it does:
  1. Starts a second API from `docker-compose.two-instances.yml` (management port 19091, fast job intervals).
  2. Reads `jobs_runs_total` from both instances over the window.
  3. Checks each job's total against one run per lock (`lockAtLeastFor`), and checks that the credential migration ran once.
  4. Returns staging to one instance.
- Exit 0 = PASS. Evidence: `target/staging-evidence/two-instances-<timestamp>.txt` (backend repo; copy it elsewhere before the next `mvn clean`).

