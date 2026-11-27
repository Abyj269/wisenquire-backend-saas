# Runbook: background jobs, OWN scope, audit log, CompanyClock

01-foundation Part B items 6–9 and 11 (Phase 1, Step 5.2). Migrations V53 (ShedLock, one-time tasks), V54 (role data scope + "Agent" template), V55 (audit log).

## Background jobs (ShedLock)
Each scheduled job takes a lock in the `shedlock` table, so with several instances only one runs it at a time.

| Job (lock name) | Schedule | Lock held at most / at least |
|---|---|---|
| `ads-sync` | cron `ads.sync.cron` (hourly at :15) | 50 min / 1 min |
| `broadcast-scheduler` | every `broadcast.scheduler.check-ms` (60 s) | 5 min / 10 s |
| `broadcast-recovery` (module 16) | every `broadcast.recovery.check-ms` (60 s, first run after 60 s) | 5 min / 10 s |
| `email-outbox` (module 02, Step 6.2) | every `email.outbox.check-ms` (10 s, first run after 10 s) | 5 min / 1 s |
| `whatsapp-receipt-reconcile` | every `whatsapp.receipts.reconcile-ms` (2 s) | 1 min / 1 s |
| `whatsapp-receipt-cleanup` | every `whatsapp.receipts.cleanup-ms` (1 h) | 30 min / 1 min |

- **Who holds a lock now:** `SELECT name, locked_by, locked_at, lock_until FROM shedlock;` (`locked_by` is the host name). Times are database time.
- **A job seems stuck** (lock_until far in the future after a crash): the lock frees itself at `lock_until` (the "at most" time). To free it at once: `DELETE FROM shedlock WHERE name = '<lock name>';`.
- **Runs per job:** Prometheus `jobs_runs_total{job="<lock name>"}` on the management port. A job whose counter stops increasing is not running anywhere.
- **Kill switch:** `APP_SHEDLOCK_ENABLED=false` (restart). Every instance then runs every job, as before Step 5.2. Only safe with a single instance.

## WhatsApp credential migration (run once)
Encrypts legacy plaintext WhatsApp credentials. Now runs **once per database**: the first start-up does it and writes a marker; later start-ups skip it.
- Check: `SELECT * FROM one_time_tasks WHERE task_key = 'whatsapp-credential-migration';`
- **Re-run it** (for example after importing old plaintext data in the Phase 6 client migration): `DELETE FROM one_time_tasks WHERE task_key = 'whatsapp-credential-migration';` and restart one instance.

## Background thread context
The automation, ads-sync and broadcast executors now carry the trace id, org/user log context, the login of the user who started the work, and the system scope of webhooks/jobs. A broadcast's log lines therefore carry the same `traceId` as the request that sent it.
- The broadcast queue is bounded: 5 threads, 1000 waiting. When full, "send" answers 409 CONFLICT "Too many broadcasts are being sent right now. Try again shortly." and changes nothing.

## OWN data scope (D14)
- `roles.data_scope`: `LEVEL` (every existing role: today's behaviour) or `OWN`.
- Enforced only when flag `own_scope.enforced` is ON for the organization (default OFF). `own_scope.include_unassigned` also shows unassigned leads and conversations to OWN users.
- **What is enforced so far:** opening one record (lead, conversation and its messages, customer, quotation, delivery). **Not yet:** lists, counts, reports, assignment rules and live chat topics: they come with modules 06–12. **Do not turn `own_scope.enforced` on for a real organization before those modules are done**, or OWN users will see other agents' records in lists but be refused when they open them.
- The "Agent" system template (BRANCH + OWN) has no organization and is not visible to any organization; modules 03/20 copy it.
- The JWT and `/auth/profile` carry `dataScope` (display only; the server re-reads the role).

## Audit log
Every POST/PUT/PATCH/DELETE on the tenant admin and settings controllers (tenant, lead settings, delivery settings, AI settings, WhatsApp config, WhatsApp schedule, ad connections, saved replies) writes one row. Secrets in the body are masked (`***`).

```sql
-- Latest changes in one organization
SELECT created_at, actor_user_id, action, entity_type, entity_id, outcome, http_status, ip
FROM audit_log WHERE organization_id = <org_id> ORDER BY created_at DESC LIMIT 100;

-- Everything one request did (traceId = X-Request-Id response header = traceId in the logs)
SELECT * FROM audit_log WHERE trace_id = '<trace id>';

-- Refused attempts in the last day
SELECT * FROM audit_log WHERE outcome = 'DENIED' AND created_at > NOW() - INTERVAL 1 DAY;
```
- `outcome`: SUCCESS, DENIED (401/402/403/404/423) or FAILED.
- If writing a row fails, the user's action still succeeds and the error is logged (`Could not write the audit record`).
- The IP is the request's remote address; see the open X-Forwarded-For finding in module 02 before relying on it.
- Kill switch: `APP_AUDIT_ENABLED=false` (restart).
- No retention job yet: the table grows; plan a retention rule with module 21.

## CompanyClock (D15)
The only API for "today", date filters and day buckets in a company's timezone (`service/CompanyClock`). The server wall-clock stays Asia/Dubai. Modules switch their own call sites (D15 table); the architecture report lists the remaining `LocalDate.now()` / `LocalDateTime.now()` / `atStartOfDay()` calls under rule (d).

## Server timezone guard (module 14, D15 / R20)
- At startup the app logs `Server timezone: <zone> (D15 server zone, guard <mode>)` and compares the JVM zone with Asia/Dubai.
- Property `app.server-zone-guard` (env `APP_SERVER_ZONE_GUARD`): `fail` refuses to start, `warn` logs an ERROR, `off` skips the check.
  - Default `warn`; the `prod` profile sets `fail`.
- **Before deploying to a prod-profile server, check its zone:** `timedatectl` (expect `Asia/Dubai`) and the JVM options (`-Duser.timezone=Asia/Dubai`, e.g. in Tomcat `setenv.sh`). The container image already sets both.
- **If the app refuses to start** with "Server timezone is … but must be Asia/Dubai": fix the zone (`TZ=Asia/Dubai`, `-Duser.timezone=Asia/Dubai`) and restart. As a stop-gap only, set `APP_SERVER_ZONE_GUARD=warn` and restart (no rebuild); stored times will be shifted until the zone is fixed.

## Auto-reply once per day (module 14)
- `whatsapp_conversations.last_auto_reply_at` is server wall-clock (Asia/Dubai) like every other column; "already replied today" uses the company's own day (`WhatsAppScheduleService.repliedToday`).
- Values written before module 14 were company-local. No backfill: for a non-Dubai company, at most one auto-reply can be skipped on the deploy day.

## Broadcast chunked send and recovery (module 16 P1)
- Flag `broadcast.chunked_tx` (V52, default OFF), per org. **ON:** a broadcast is sent in chunks of 50 with no DB transaction during any Meta call; each recipient's result is saved right after its own call; `broadcasts.last_heartbeat_at` (V80) is stamped per chunk. **OFF:** the original single-transaction path (kept for one release as the rollback).
- Recipient statuses during a run: `SENDING_ITEM` = claimed, its Meta call not started; `ATTEMPTING` = its Meta call has started. Both are normal while a broadcast sends.
- **Recovery job** `broadcast-recovery`: a broadcast still `SENDING` with a heartbeat older than 5 minutes is resumed; claimed recipients without a wamid go back to `PENDING`, recipients with a wamid are never sent again. Only chunked runs (heartbeat set) are resumed: an old-path run that died rolled its results back, so resuming it would send everyone twice. Log lines: `[BROADCAST RECOVERY] Resuming broadcast ID …`.
- **At most once (Step 5.22, owner decision A):** the recovery never resends a recipient whose Meta call had started (`ATTEMPTING`, no wamid). It becomes `UNCONFIRMED` ("Sending was interrupted …; it was not resent, to avoid a duplicate"): Meta may or may not have delivered it. Expect at most one such recipient per broadcast per crash; there are never double sends. To send it again on purpose, use a new broadcast to that number.
- **Stuck in SENDING on the old path (flag OFF):** unchanged from before; check `broadcast_recipients` and Meta before re-sending by hand.

```sql
INSERT INTO feature_flag_overrides (flag_key, organization_id, enabled, set_by, reason)
VALUES ('broadcast.chunked_tx', <org_id>, 1, '<you>', 'module 16 chunked broadcast send');
DELETE FROM feature_flag_overrides WHERE flag_key = 'broadcast.chunked_tx' AND organization_id = <org_id>;
-- broadcasts the recovery would pick up now (last_heartbeat_at is server wall-clock, Asia/Dubai; put the current Dubai time minus 5 minutes):
SELECT id, company_id, last_heartbeat_at FROM broadcasts WHERE status = 'SENDING' AND deleted_at IS NULL AND last_heartbeat_at < '<dubai now - 5 min>';
```

## Architecture test (fail mode since Step 5.22)
- `ArchitectureReportTest` fails the build on any finding of rules (a) TenantAccess on id-taking service methods, (b) `findById` without a record check, (c) repositories in controllers, or (d) server-date calls, unless it is listed in `src/test/resources/architecture/allow-list.txt` as `finding || REASON || note`.
- REASON codes are defined in the test (`TIMESTAMP`, `COMPANY_DAY_HELPER`, `SYSTEM_PATH`, `CHECKED_CALLER`, `CHECKED_OTHERWISE`). A new entry needs owner approval.
- The test also fails on a stale entry: when you fix a finding, delete its line.
- Every finding, allowed or not, is written to `target/architecture/violations.txt`.

