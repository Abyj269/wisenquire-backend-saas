# Runbook: lead settings write scope (module 04 P1, F-12)

## What changed
- New flag `lead_settings.company_wide_writes` (V110), **default OFF**.
- **OFF (today):** anyone with `LEAD_SETTINGS:ADD/EDIT/DELETE` on the company may change its statuses, purposes, lost reasons, sources, custom fields, predefined field settings and company lead preferences. That includes branch-level roles.
- **ON for an org:** those writes also need company-wide scope (COMPANY or ORGANIZATION level, or the super admin). Branch-level users get 403 `FORBIDDEN`. Reads are unchanged: everyone who could read still can, because lead forms need the lists.
- The frontend follows the flag. With it ON, branch-level users see the settings read-only (no add/edit/delete buttons, toggles, drag-reorder or Save).
- Unchanged by the flag: F-10 (a default status from another company or org is refused) has been fixed since Phase -1, and now has a regression test.

## Before turning it on: who loses rights
Read-only query on the org's database:
```sql
SELECT r.organization_id, r.id AS role_id, r.role_name, COUNT(DISTINCT u.id) AS users
FROM roles r
JOIN role_permissions p ON p.role_id = r.id AND p.module_name = 'LEAD_SETTINGS'
     AND (p.can_add = 1 OR p.can_edit = 1 OR p.can_delete = 1)
LEFT JOIN users u ON u.role_id = r.id AND u.is_active = 1
WHERE r.role_level = 'BRANCH'
GROUP BY r.organization_id, r.id, r.role_name;
```
Send those orgs the release note first. If a branch manager still needs to manage settings, move them to a company-level role.

## Turning it on or off
```sql
-- one organization
INSERT INTO feature_flag_overrides (flag_key, organization_id, enabled, set_by, reason)
VALUES ('lead_settings.company_wide_writes', <org_id>, 1, '<you>', 'F-12 company-wide lead settings');
-- back to the default for that organization
DELETE FROM feature_flag_overrides WHERE flag_key = 'lead_settings.company_wide_writes' AND organization_id = <org_id>;
-- everyone without an override (after the release note has gone out)
UPDATE feature_flags SET enabled_default = 1 WHERE flag_key = 'lead_settings.company_wide_writes';
```
Changes take effect within 60 s (flag cache). The frontend picks them up on the next flag refresh or login.

## Release note (draft)
> **Lead Settings:** pipeline statuses, sources, purposes, lost reasons, custom fields and company lead preferences are shared by the whole company, so only company-level and organization-level admins can now change them. Branch managers can still view them and use them on leads. If a branch manager needs to manage them, ask your administrator to give them a company-level role.

## Rollback
Turn the flag off (above). V110 only inserts the flag row; leave it in place.
