# Runbook: staging seed data and reset

Plan Phase 0, Step 3.3; `docs/saas-modules/01-foundation.md` Part A item 5.

## What the seed creates
Deterministic: a fixed random seed per tenant, so every reset gives the same rows (dates are relative to the run time).

| Tenants | Size | Companies × branches | Leads | Customers | Conversations | Messages |
|---|---|---|---|---|---|---|
| t01 | large, **Asia/Kolkata** | 2 × 5 | 50,000 | 35,000 | 20,000 | 200,000 |
| t02–t05 | medium | 2 × 2 | 2,000 each | 1,500 | 800 | 8,000 |
| t06–t20 | small | 1 × 2 | 100 each | 80 | 40 | 400 |
| **Total** | 20 tenants, 213 users | 25 companies, 56 branches | 59,500 | 42,200 | 23,800 | 238,000 |

- **Timezones:** companies cycle through the 7 allowed zones (Asia/Kolkata, Dubai, Muscat, Riyadh, Qatar, Bahrain, Kuwait).
- **Golden timezone rows:** the first leads of each tenant and the first message of some chats sit at 23:55, 23:59, 00:00 and 00:05 company-local time, stored as server (Asia/Dubai) wall-clock. For Kolkata that is 22:25–22:35 server time.
- **Personas per tenant:** ORG_ADMIN; COMPANY_MGR per company; BRANCH_MGR and two agents per branch. Agents use the "Agent" role (BRANCH level). The OWN data scope (D14) is not built yet; when it is, the seed sets the Agent role to OWN.
- **Assignment:** leads and conversations are 40% agent 1, 30% agent 2, 30% unassigned.
- **WhatsApp:** one config per branch (`phone_number_id = stg-<tenant>-<branch>`), credentials encrypted with the API's key, all pointed at the Meta mock. 30% of chats have the 24-hour reply window open.
- **Company defaults:** the app's own default pipeline, sources and lost reasons (`LeadSettingsService.seedDefault*`), the same as a company created in the UI.
- `personas.csv` (next to this runbook, git-ignored) lists every user's email, tenant, company, branch and timezone.

## Seed a fresh database
```bash
cd wse_leads_saas_backend/deploy/staging
bash scripts/seed.sh          # full scale, about 1-2 minutes
bash scripts/seed.sh --ci     # tiny scale, same structure (8 tenants; used by CI)
```

## Reset (wipe the data and re-seed)
```bash
bash scripts/reset.sh         # drop + recreate the DB, Flyway, full seed, restart api + frontend
bash scripts/reset.sh --ci    # same with the tiny seed
bash scripts/reset.sh --empty # migrate only, no tenants
```
What it does, in order: stop `api` and `frontend` → `DROP DATABASE` + `CREATE DATABASE` (utf8mb4_0900_ai_ci) → `migrate` (Flyway) → seed → start `api` and `frontend` and wait for health. Object storage and the media cache are kept; use `down.sh --wipe` to delete everything.

Reset logs out every session (the users are recreated) and leaves old OTP emails in Mailpit.

## Safety guards
The generator is **test-scope code** (`src/test/java/com/leads/tools/seed/`): it is never in the WAR or the container JAR. It refuses to run unless all of these hold:
1. `SEED_TARGET=staging` is set (the scripts set it).
2. `DB_NAME` matches `wisenquire_staging*` or `wisenquire_ci*`. `reset.sh` additionally accepts only `wisenquire_staging*`.
3. `ADS_CREDENTIALS_ENCRYPTION_KEY` is set (it must be the API's key, or the API cannot decrypt the seeded WhatsApp credentials).
4. The JVM zone is Asia/Dubai (the container's), so LocalDateTime columns match the app.
5. After start-up and **before any write**: the connected database name and URL are the expected ones, and neither the `windows-dev` nor the `prod` profile is active.
6. The `organizations` table is empty.

## CI
The `container` job in `.github/workflows/backend-ci.yml` runs the tiny seed against the CI MySQL after the Flyway step, starts the app on that data, then logs in as `agent1.c1b1@t01.staging.test` (OTP via a Mailpit service) and asserts own branch 200, other branch 403, other tenant 403. A schema change that breaks the seed's SQL fails CI there.

## When the schema changes
The bulk tables (`customers`, `leads`, `whatsapp_conversations`, `whatsapp_messages`) are written with explicit SQL in `StagingSeed.bulk`. A migration that adds a NOT NULL column without a default to one of them must also update that SQL; the CI seed step fails until it does.
