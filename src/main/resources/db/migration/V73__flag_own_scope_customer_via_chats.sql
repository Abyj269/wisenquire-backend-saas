-- Module 07 P1. Default OFF: with own_scope.enforced, an OWN user sees a customer only through a lead
-- assigned to them (or an unassigned one with own_scope.include_unassigned).
-- ON (after module 12 P1): also through a WhatsApp chat assigned to them.
INSERT INTO feature_flags (flag_key, enabled_default, description, owner, expires_at) VALUES
('own_scope.customer_via_chats', 0, 'OWN users also see customers through chats assigned to them. Turn on with module 12 (conversation OWN rules).', 'module-07', '2027-12-31');
