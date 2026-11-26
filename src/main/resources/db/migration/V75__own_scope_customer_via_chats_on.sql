-- Module 12 P1 done: conversation OWN rules are live, so module 07's chat half (an OWN user also sees a
-- customer through a chat assigned to them) becomes the default. It only has an effect for orgs with
-- own_scope.enforced ON (default OFF). Per-org overrides still win.
UPDATE feature_flags SET enabled_default = 1 WHERE flag_key = 'own_scope.customer_via_chats';
