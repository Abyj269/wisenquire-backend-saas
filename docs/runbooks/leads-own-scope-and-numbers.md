# Runbook: leads and customers, OWN scope, and document numbers (modules 06–07 P1)

## OWN scope for leads (flags already exist since module 01)
- `own_scope.enforced` (default OFF), per org. **ON:** users whose role has data scope OWN (e.g. the "Agent" template) see and act only on leads assigned to them. This covers:
  - the lead list and Kanban (the total counts only theirs);
  - the dashboard numbers, labelled "Your numbers";
  - reports built on the lead list;
  - a customer's lead list;
  - every single-lead action (open, edit, notes, timeline, convert, mark lost).
- `own_scope.include_unassigned` (default OFF). **ON:** OWN users also see unassigned leads and may **claim** one for themselves, either with the edit form (assignee = me) or with the assign action. They never assign to anyone else, reassign or unassign.
- A lead created by an OWN user is automatically assigned to them; naming another assignee is refused (403).
- WhatsApp auto-created leads and automations run as the system and are not affected.
- LEVEL roles (managers) are unchanged.

```sql
INSERT INTO feature_flag_overrides (flag_key, organization_id, enabled, set_by, reason)
VALUES ('own_scope.enforced', <org_id>, 1, '<you>', 'D14 OWN scope'),
       ('own_scope.include_unassigned', <org_id>, 1, '<you>', 'agents may claim unassigned leads');
DELETE FROM feature_flag_overrides WHERE flag_key IN ('own_scope.enforced','own_scope.include_unassigned') AND organization_id = <org_id>;
```

**Who is affected:** users whose role has `data_scope = 'OWN'`. Existing client roles stay LEVEL (owner decision); new self-signup tenants get an OWN "Agent" template.

## Customers (module 07 P1)
- **F-11 fix (no flag):** the phone typeahead (`/customers/suggest`, used when creating a lead and in the inbox) only suggests customers with a lead in the caller's branches, the same rule as the customer list. Lead creation is unaffected: an existing customer with that phone is still reused, never duplicated.
- **With `own_scope.enforced` on:** OWN users see only customers of their leads, plus unassigned leads with `include_unassigned`. This applies to the list (titled "My Customers"), the typeahead, and the detail, logs and notes. A deep link to another customer shows "Customer not available".
- **Flag `own_scope.customer_via_chats` (V73, default OFF):** also shows customers through a WhatsApp chat assigned to the user. **Keep it OFF until module 12 (conversation OWN rules) is live**, then turn it on per org:
```sql
INSERT INTO feature_flag_overrides (flag_key, organization_id, enabled, set_by, reason)
VALUES ('own_scope.customer_via_chats', <org_id>, 1, '<you>', 'after module 12');
```
- V72 adds indexes `leads(customer_id, assigned_to_id)` and `whatsapp_conversations(assigned_agent_id, lead_id)`. They are online, additive DDL; on a large live table, run the deploy outside busy hours.

## Company timezone (no flag)
- Lead list, Kanban, dashboard and report date filters now cut days at the **company's** midnight. Companies on Asia/Dubai (the server zone) see no change.
- The year in new document numbers is the company-local year: a lead created at 00:10 IST on 1 January is numbered in the new year.
- **Server timezone (risk R20):** stored times are taken to be Asia/Dubai wall-clock. The production JVM or container must run with `TZ=Asia/Dubai` (or `-Duser.timezone=Asia/Dubai`). Check this on the live server; it is not set in any file in this repo.

## Document numbers without the company id (V70, V71)
- V70 gives every company a random, unique 6-character `document_code` (letters and digits). New companies get one automatically.
- Flag `documents.company_code_prefix` (default OFF), per org. **ON:** new lead, quotation and delivery numbers become `LD-<code>-<year>-<seq>` (e.g. `LD-K7Q2XM-2026-000045`) instead of `LD-<companyId>-...`. Existing numbers never change.
- A number is only a label. Opening a record by id or number still goes through the tenant checks, so guessing a number gives no access. The code only stops numbers showing internal ids.

```sql
INSERT INTO feature_flag_overrides (flag_key, organization_id, enabled, set_by, reason)
VALUES ('documents.company_code_prefix', <org_id>, 1, '<you>', 'hide internal company id in numbers');
SELECT id, name, document_code FROM companies WHERE organization_id = <org_id>;
```

## Rollback
- Turn the flags off.
- V70 and V71 are additive (a new column, a unique key, a flag row); leave them in place. An older WAR ignores `document_code`.
