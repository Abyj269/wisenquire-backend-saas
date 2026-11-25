# Runbook: background jobs, OWN scope, audit log, CompanyClock

01-foundation Part B items 6–9 and 11 (Phase 1, Step 5.2). Migrations V53 (ShedLock, one-time tasks), V54 (role data scope + "Agent" template), V55 (audit log).

## Background jobs (ShedLock)
Each scheduled job takes a lock in the `shedlock` table, so with several instances only one runs it at a time.

| Job (lock name) | Schedule | Lock held at most / at least |
|---|---|---|
| `ads-sync` | cron `ads.sync.cron` (hourly at :15) | 50 min / 1 min |
| `broadcast-scheduler` | every `broadcast.scheduler.check-ms` (60 s) | 5 min / 10 s |
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
