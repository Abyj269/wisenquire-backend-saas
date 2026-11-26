# Runbook: deliveries (module 09 P1)

## OWN scope (flag `own_scope.enforced`, already exists)
With it ON for an org, users whose role is OWN (the "Agent" template):
- see only deliveries **assigned to them** or **on their leads**: the list, the calendar (no "All Deliveries"), the unassigned-today count, and each delivery and its timeline;
- can record what happened (out for delivery, delivered, not delivered, request postpone, request close) **only on deliveries assigned to them**, even when the delivery is visible through their lead;
- never assign, reassign, approve, reject or cancel deliveries, whatever their permissions. They cannot claim an unassigned delivery either; a manager assigns it.

Managers (LEVEL roles) are unchanged.

## Company "today" (no flag)
- **Backend:** "the date cannot be in the past" when scheduling or postponing, and the unassigned-today count, use the company's date (Asia/Kolkata at 00:30 IST is already the new day).
- **Frontend:** the date pickers' default and minimum, and the calendar's "today" and month, use the company's date instead of the UTC or browser date.

## Delivery settings: company-wide writes (flag `delivery_settings.company_wide_writes`, V112, default OFF)
- **OFF (today):** anyone with `DELIVERIES:APPROVE` on the company may change failure reasons and the company delivery settings, including branch-level roles.
- **ON for an org:** those writes need COMPANY or ORGANIZATION level. Branch-level users get 403 and see the settings read-only. Reads are unchanged.

Who would lose rights (read-only query):
```sql
SELECT r.organization_id, r.id AS role_id, r.role_name, COUNT(DISTINCT u.id) AS users
FROM roles r
JOIN role_permissions p ON p.role_id = r.id AND p.module_name = 'DELIVERIES' AND p.can_approve = 1
LEFT JOIN users u ON u.role_id = r.id AND u.is_active = 1
WHERE r.role_level = 'BRANCH'
GROUP BY r.organization_id, r.id, r.role_name;
```
On or off per org:
```sql
INSERT INTO feature_flag_overrides (flag_key, organization_id, enabled, set_by, reason)
VALUES ('delivery_settings.company_wide_writes', <org_id>, 1, '<you>', 'F-12 company-wide delivery settings');
DELETE FROM feature_flag_overrides WHERE flag_key = 'delivery_settings.company_wide_writes' AND organization_id = <org_id>;
```

**Also fixed:** updating a failure reason without `sortOrder` or `isActive` keeps their values (it was a 500).

## Release note (draft)
> **Deliveries:** delivery agents can update only the deliveries assigned to them, and dates such as "today" now follow your company's timezone. *(When enabled for your organization:)* only company-level admins can change delivery settings and failure reasons; branch managers can still view them.

## Rollback
Turn the flags off. V112 only inserts the flag row.
