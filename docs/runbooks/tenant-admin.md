# Runbook: tenant admin (module 03 P1)

## What changed
- **V65 `organizations.slug`:** unique and URL-safe, filled from `code` (lower case, runs of other characters become `-`).
  - Empty results become `org-<id>`; duplicates get `-<id>`.
  - `organizations.name` is no longer unique (F-6), so two tenants may share a display name.
  - New organizations get a slug from the code, with `-2`, `-3`, … added if it is taken. The slug never changes after creation.
- **V66 flag `tenant.timezone_allowlist` (default OFF):**
  - When ON for an org, a company's timezone may only be *changed to* one of `Asia/Kolkata, Asia/Dubai, Asia/Muscat, Asia/Riyadh, Asia/Qatar, Asia/Bahrain, Asia/Kuwait` (D15 option A, extended). Anything else returns 400 `VALIDATION_FAILED`, with `details.allowed`.
  - A company that already has another zone can still be edited as long as its zone is left as it is.
- **Roles:** `dataScope` (LEVEL / OWN) is now writable. A role created without it is LEVEL; on update, a missing value means unchanged. A user whose own role is OWN can only create or edit OWN roles.
- **Users:**
  - `GET /tenant/users` returns only id, names, role name and active to callers without user-management rights (agents, assignment pickers). ORG admins, the super admin and `USER_MANAGEMENT:VIEW` still get the full user.
  - Create and update check the target organization, the role's organization and (for update) the user's *current* companies and branches before anything else.
- **Tenant status:** `TenantAccess.organization()` now applies D12 tenant status to org-level writes too (companies, roles, users, the org itself). A SUSPENDED org gets 423 and a PAST_DUE org past grace gets 402, on writes only.

## Start-up report
`CompanyTimezoneReport` logs one WARN per company whose timezone is outside the 7 zones:

`D15: company <name> (id <id>, org <orgId>) uses timezone '<zone>', which is not one of the 7 supported zones`

Before you turn on `tenant.timezone_allowlist` for an org, move that org's flagged companies to a supported zone in Tenant Admin → Companies. Leaving them is safe, but staff cannot pick that zone again once they change it.

## Turning the allow-list on or off
There is no admin UI for flags yet (module 21). Use SQL, as in `flags-errors-tenant-status.md`:
```sql
INSERT INTO feature_flag_overrides (flag_key, organization_id, enabled, set_by, reason)
VALUES ('tenant.timezone_allowlist', <org_id>, 1, '<you>', 'D15 allow-list');
-- rollback
DELETE FROM feature_flag_overrides WHERE flag_key = 'tenant.timezone_allowlist' AND organization_id = <org_id>;
```
SQL changes take effect within 60 s (flag cache).

## Rollback of the release
- V65 and V66 are additive; leave them in place.
- `organizations.slug` is NOT NULL with no DB default, so an older WAR could not create organizations. Before rolling back the WAR, run `ALTER TABLE organizations MODIFY slug VARCHAR(80) NULL;`. Existing rows keep their slugs.
- The dropped UNIQUE(name) does not need to come back.
