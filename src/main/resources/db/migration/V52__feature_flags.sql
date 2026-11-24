-- 01-foundation Part B item 4 (D13 feature flags). Flags are operational and platform-owned;
-- they are not entitlements. Keys are code-owned (com.leads.flags.FeatureFlag); a test checks
-- that these rows and the enum match. Every flag starts OFF (the old behaviour).
CREATE TABLE feature_flags (
    flag_key        VARCHAR(100) NOT NULL,
    enabled_default BIT NOT NULL DEFAULT 0,
    description     VARCHAR(500) NOT NULL,
    owner           VARCHAR(100) NOT NULL,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at      DATE NOT NULL,
    PRIMARY KEY (flag_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE feature_flag_overrides (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    flag_key        VARCHAR(100) NOT NULL,
    organization_id BIGINT NOT NULL,
    enabled         BIT NOT NULL,
    set_by          VARCHAR(150) NOT NULL,
    set_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    reason          VARCHAR(500) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_flag_override_org (flag_key, organization_id),
    CONSTRAINT fk_flag_override_flag FOREIGN KEY (flag_key) REFERENCES feature_flags (flag_key),
    CONSTRAINT fk_flag_override_org FOREIGN KEY (organization_id) REFERENCES organizations (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO feature_flags (flag_key, enabled_default, description, owner, expires_at) VALUES
('errors.uniform_not_found',     0, 'D12: missing and out-of-scope records both return 404 NOT_FOUND (F-9). Off: 400 / 403 as before.', 'module-01', '2027-06-30'),
('own_scope.enforced',           0, 'D14: OWN data scope enforced for roles with data_scope = OWN.', 'module-01', '2027-12-31'),
('own_scope.include_unassigned', 0, 'D14: OWN agents also see unassigned leads and conversations.', 'module-01', '2027-12-31'),
('auth.subject_user_id',         0, 'Module 02: JWT subject is the user id instead of the email.', 'module-02', '2027-06-30'),
('auth.refresh_tokens',          0, 'D11 / module 02: short access tokens plus refresh tokens.', 'module-02', '2027-06-30'),
('broadcast.chunked_tx',         0, 'Module 16: one transaction per recipient chunk instead of per broadcast (P-3a).', 'module-16', '2027-06-30'),
('broadcast.queue_v2',           0, 'S8 / module 16: broadcast queue with pacing.', 'module-16', '2027-12-31'),
('webhook.async_inbox',          0, 'S7 / module 12: acknowledge webhooks first, process from an inbox table.', 'module-12', '2027-12-31'),
('reports.sql_v2',               0, 'S4 / module 10: reports computed in SQL with company-timezone buckets.', 'module-10', '2027-12-31'),
('signup.public',                0, 'Module 20: public self-signup is open.', 'module-20', '2028-06-30'),
('wa.cost_display',              0, 'Module 23: WhatsApp cost display.', 'module-23', '2028-06-30'),
('wa.tenant_send_limit',         0, 'Module 23: optional tenant-set monthly WhatsApp send limit.', 'module-23', '2028-06-30'),
('billing.enforce',              0, 'Module 22: plan entitlements are enforced (otherwise unlimited).', 'module-22', '2028-06-30'),
('billing.einvoice',             0, 'Module 22: India e-invoicing (IRN) is used.', 'module-22', '2028-06-30'),
('branding.runtime',             0, 'Module 24: per-tenant branding from runtime config (single FE build).', 'module-24', '2028-06-30');
