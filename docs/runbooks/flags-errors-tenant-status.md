# Runbook: feature flags, error codes, tenant status, actor memo

01-foundation Part B items 1–5 (Phase 1, Step 5.1). Migrations V51 (organization status) and V52 (feature flags).

## Feature flags (D13)
- **Where:** `feature_flags` (global default) and `feature_flag_overrides` (per organization). Keys are code-owned: `com.leads.flags.FeatureFlag`. A test fails if the table and the enum differ.
- **Resolution:** the org's override, else the default. Cached per org for 60 s; a write through `FeatureFlags` evicts this instance at once. A change made directly in SQL takes effect within 60 s.
- **Every flag starts OFF** (old behaviour).
- **The platform API (with audit) comes with module 21.** Until then, change flags with SQL on the database the app uses:

```sql
-- Turn a flag on for one organization (reason is required)
INSERT INTO feature_flag_overrides (flag_key, organization_id, enabled, set_by, reason)
VALUES ('errors.uniform_not_found', <org_id>, 1, '<your name>', '<why>')
ON DUPLICATE KEY UPDATE enabled = VALUES(enabled), set_by = VALUES(set_by), reason = VALUES(reason), set_at = CURRENT_TIMESTAMP(6);

-- Back to the default for that organization
DELETE FROM feature_flag_overrides WHERE flag_key = 'errors.uniform_not_found' AND organization_id = <org_id>;

-- Change the global default (all organizations without an override)
UPDATE feature_flags SET enabled_default = 1 WHERE flag_key = '<key>';
```
- **What the browser sees:** `GET /api/v1/me/feature-flags` returns the exposed flags for the caller's own organization.
- **Hygiene:** each flag has an owner and an `expires_at` (enum and table). CI prints a warning for expired flags (`FeatureFlagHygieneTest`). Remove a flag once it has been at 100% for 2 weeks.

| Flag | Default | Owner | What it does when ON |
|---|---|---|---|
| `errors.uniform_not_found` | OFF | module 01 | Missing and out-of-scope records both return **404 NOT_FOUND** with the same message (fixes F-9). OFF: missing 400, out of scope 403, as before. Permission denials stay 403 either way. |
| *(see "Flag record" below for every flag)* | | | |

**Rollback** for any flag: delete the override or set the default back to 0; effective within 60 s.

## Error codes (D12)
Every error body is now:
```json
{ "code": "NOT_FOUND", "message": "Record not found.", "details": {}, "traceId": "…" }
```
- `message` is unchanged, so the current frontend keeps working.
- `traceId` equals the `X-Request-Id` response header and the `traceId` in the logs: search the logs with it.
- The closed list of codes is `com.leads.exception.ErrorCode` and the `ErrorCode` schema in OpenAPI.
- A 401 with code `SESSION_REVOKED` means the token's session version is stale (the user's access was changed or the role edited): log in again.
- A 429 `RATE_LIMITED` carries `Retry-After: 60`.

## Tenant status (V51)
`organizations.status`: `TRIAL`, `ACTIVE` (all existing organizations), `PAST_DUE`, `SUSPENDED`, `CANCELLED`. Enforced in `TenantAccess.company()` for API requests. Reads are always allowed; writes are blocked:

| Status | Writes |
|---|---|
| SUSPENDED, CANCELLED | 423 `TENANT_SUSPENDED` |
| PAST_DUE, more than `app.tenant.past-due-grace-days` (default 7) after `status_changed_at` | 402 `TENANT_PAST_DUE` |
| TRIAL after `trial_ends_at` | 402 `TENANT_TRIAL_EXPIRED` |

- The platform super admin and system jobs/webhooks are not blocked by this check.
- Nothing sets a non-ACTIVE status yet (modules 20, 21, 22 will). To suspend by hand: `UPDATE organizations SET status = 'SUSPENDED', status_changed_at = NOW(6) WHERE id = <org_id>;` and to restore: `status = 'ACTIVE'`. Effective immediately (no cache).
- `is_active` still exists and still blocks everything when 0 (expand/contract: it is removed in a later phase).

## Actor memo (Part B item 1)
- The JWT filter loads the signed-in user once per request (with role, permissions and allowed companies/branches) and `TenantAccess.actor()` and `AuthService.getCurrentUser()` reuse it. STOMP: once per frame.
- **Kill switch:** `APP_TENANT_ACTORMEMO_ENABLED=false` (environment) or `app.tenant.actor-memo.enabled=false`, then restart. Use it if anything behaves oddly right after a user's own access was edited, or on a `LazyInitializationException` mentioning `User`.

## Flag record (Phase 1 close, 2026-11-27)
Defaults are the `feature_flags.enabled_default` values shipped by the migrations. **Prod** = every existing client WAR and the SaaS live deployment: defaults only, no overrides until the owner turns a flag on per org.
**Staging** = the seeded synthetic tenants. **Dev / IT** = defaults; the integration tests turn flags on per test, and the isolation matrix runs with the F-12 and AI flags ON.

| Flag | Default (dev, prod) | Staging | Owner | Expires | In use since | ON means |
|---|---|---|---|---|---|---|
| `errors.uniform_not_found` | OFF | OFF | module-01 | 2027-06-30 | Step 5.2 | missing and out-of-scope records both 404 |
| `errors.ui_handler` | OFF | OFF | module-01 | 2027-06-30 | Step 5.3 | global D12 error UI in the frontend |
| `own_scope.enforced` | OFF | **ON** (seed, every org) | module-01 | 2027-12-31 | modules 06–12, 16, 18 | roles with data_scope OWN see only their own records |
| `own_scope.include_unassigned` | OFF | OFF | module-01 | 2027-12-31 | modules 06, 12 | OWN users also see and may claim unassigned leads/chats |
| `own_scope.customer_via_chats` | **ON** (V75) | ON | module-07 | 2027-12-31 | module 12 | OWN users also see customers through their chats (inert while `own_scope.enforced` is off) |
| `tenant.timezone_allowlist` | OFF | OFF | module-03 | 2027-12-31 | module 03 | company timezones limited to the 7 year-1 zones |
| `lead_settings.company_wide_writes` | OFF | OFF | module-04 | 2027-12-31 | modules 04, 15 | lead settings and saved-reply writes need company-wide scope |
| `products.company_wide_writes` | OFF | OFF | module-05 | 2027-12-31 | module 05 | product writes need company-wide scope |
| `delivery_settings.company_wide_writes` | OFF | OFF | module-09 | 2027-12-31 | module 09 | delivery settings writes need company-wide scope |
| `documents.company_code_prefix` | OFF | OFF | module-06 | 2027-12-31 | module 06 | new document numbers carry the company's random code |
| `broadcast.chunked_tx` | OFF | OFF | module-16 | 2027-06-30 | module 16 | chunked broadcast send, recovery, streamed audience |
| `ai.short_transactions` | OFF | OFF | module-18 | 2027-06-30 | module 18 | no transaction or counter lock during AI provider calls |
| `auth.subject_user_id` | OFF | OFF | module-02 | 2027-06-30 | Step 6.1 | JWT subject is the user id (old email tokens still accepted) |
| `auth.refresh_tokens` | OFF | OFF | module-02 | 2027-06-30 | Step 6.1 (keep OFF until the frontend refresh step) | 15-minute access + rotating 7-day refresh tokens (`docs/runbooks/auth-sessions.md`) |
| `broadcast.queue_v2` | OFF | OFF | module-16 | 2027-12-31 | not yet (P5) | durable broadcast queue |
| `webhook.async_inbox` | OFF | OFF | module-12 | 2027-12-31 | not yet (P5) | webhook inbox table |
| `reports.sql_v2` | OFF | OFF | module-10 | 2027-12-31 | not yet (P5) | reports in SQL |
| `signup.public` | OFF | OFF | module-20 | 2028-06-30 | not yet (Phase 2) | public signup |
| `billing.enforce`, `billing.einvoice` | OFF | OFF | module-22 | 2028-06-30 | not yet (Phase 3) | entitlements enforced / India e-invoicing |
| `wa.cost_display`, `wa.tenant_send_limit` | OFF | OFF | module-23 | 2028-06-30 | not yet (Phase 3) | WhatsApp cost display / tenant send limit |
| `branding.runtime` | OFF | OFF | module-24 | 2028-06-30 | not yet (Phase 4) | runtime per-tenant branding |

- Rule (DoD 3): a flag that has been ON for every org for 2 weeks is removed with its old code path. The two "kept for one release" rollbacks, `broadcast.chunked_tx` and `ai.short_transactions`, expire on 2027-06-30.
- Check the live state: `SELECT f.flag_key, f.enabled_default, o.organization_id, o.enabled FROM feature_flags f LEFT JOIN feature_flag_overrides o ON o.flag_key = f.flag_key ORDER BY f.flag_key;`

