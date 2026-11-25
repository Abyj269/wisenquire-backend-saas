-- 01-foundation Part B item 8 (D14 OWN data scope). Expand only.
-- Every existing role becomes LEVEL = today's behaviour (ORGANIZATION / COMPANY / BRANCH).
-- OWN is enforced only for orgs with flag own_scope.enforced ON (default OFF).
ALTER TABLE roles
    ADD COLUMN data_scope ENUM('LEVEL','OWN') NOT NULL DEFAULT 'LEVEL';

-- The "Agent" system role template (owner decision: BRANCH + OWN). organization_id IS NULL:
-- roles are listed per organization, so existing organizations never see it. Signup (module 20)
-- and tenant admin (module 03) copy it into an organization.
INSERT INTO roles (is_system_role, created_at, organization_id, updated_at, role_name, description, role_level, data_scope)
VALUES (1, CURRENT_TIMESTAMP(6), NULL, CURRENT_TIMESTAMP(6), 'Agent',
        'System template: branch agent who sees only their own leads, conversations, customers, quotations and deliveries.',
        'BRANCH', 'OWN');

SET @agent_template = LAST_INSERT_ID();

INSERT INTO role_permissions (role_id, module_name, can_view, can_add, can_edit, can_delete, can_export, can_approve, can_assign, can_print) VALUES
(@agent_template, 'LEADS',              1, 1, 1, 0, 0, 0, 0, 1),
(@agent_template, 'CUSTOMERS',          1, 1, 1, 0, 0, 0, 0, 1),
(@agent_template, 'QUOTATIONS',         1, 1, 1, 0, 0, 0, 0, 1),
(@agent_template, 'DELIVERIES',         1, 0, 1, 0, 0, 0, 0, 1),
(@agent_template, 'WHATSAPP_LIVE_CHAT', 1, 1, 1, 0, 0, 0, 0, 0),
(@agent_template, 'AI_ASSISTANT',       1, 0, 0, 0, 0, 0, 0, 0);
