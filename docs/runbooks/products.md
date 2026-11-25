# Runbook: product catalogue (module 05 P1)

## What changed
- **Deleting a product:**
  - A product that no lead and no quotation uses is deleted, as before.
  - A product that is used (a `lead_products` or `quotation_items` row) is now **kept and set inactive**, and the API answers `{"outcome": "DEACTIVATED"}`. It used to fail with a 500 because of the foreign keys.
  - An inactive product disappears from the catalogue list and from the lead and quotation pickers. Existing leads and quotations keep showing it.
  - The screen says which of the two happened.
- **Updating a product:** a request without `isActive` or `currency` keeps the current value (a missing `isActive` used to be a 500). `unitPrice`, `productCode` and `productName` are still required (400).
- Product writes are now in the audit log (`entity_type = products`).
- **New flag `products.company_wide_writes` (V111), default OFF:**
  - **OFF (today):** anyone with `PRODUCT_CATALOG:ADD/EDIT/DELETE` may change the company's catalogue, including branch-level roles.
  - **ON for an org:** product create, update and delete need company-wide scope (COMPANY or ORGANIZATION level). Branch-level users get 403 and see the catalogue read-only. Reading is unchanged.
  - This flag is separate from `lead_settings.company_wide_writes`, so the two can be switched independently.

## Before turning the flag on: who loses rights
```sql
SELECT r.organization_id, r.id AS role_id, r.role_name, COUNT(DISTINCT u.id) AS users
FROM roles r
JOIN role_permissions p ON p.role_id = r.id AND p.module_name = 'PRODUCT_CATALOG'
     AND (p.can_add = 1 OR p.can_edit = 1 OR p.can_delete = 1)
LEFT JOIN users u ON u.role_id = r.id AND u.is_active = 1
WHERE r.role_level = 'BRANCH'
GROUP BY r.organization_id, r.id, r.role_name;
```

## Turning it on or off
```sql
INSERT INTO feature_flag_overrides (flag_key, organization_id, enabled, set_by, reason)
VALUES ('products.company_wide_writes', <org_id>, 1, '<you>', 'F-12 company-wide product catalogue');
DELETE FROM feature_flag_overrides WHERE flag_key = 'products.company_wide_writes' AND organization_id = <org_id>;
```
Changes take effect within 60 s.

## Reactivating a deactivated product
In Settings → Products, tick "Show inactive" and press Reactivate (needs product edit rights). By SQL if needed:
```sql
SELECT id, product_code, product_name FROM product_masters WHERE company_id = <company_id> AND is_active = 0;
UPDATE product_masters SET is_active = 1 WHERE id = <product_id> AND company_id = <company_id>;
```

## Release note (draft)
> **Products:** deleting a product that is already used on leads or quotations now keeps it for their history and marks it inactive, so it no longer appears in the catalogue or when adding products. Unused products are deleted as before.
> *(When the flag is turned on:)* The product catalogue is shared by the whole company, so only company-level and organization-level admins can change it. Branch managers can still view it and add products to leads.

## Rollback
- Turn the flag off.
- The deactivate-instead-of-500 delete has no flag; reverting it means going back to the 500. V111 only inserts the flag row.
