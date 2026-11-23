-- Branch-wise context window + usage cap + feature-toggle guardrails. Row
-- creation is lazy (like branch_trusted_ips) — a branch with no row here yet
-- simply uses these same conservative defaults in the service layer, never
-- "unlimited". No backfill needed for existing branches.
CREATE TABLE ai_branch_settings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    branch_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    context_max_messages INT NOT NULL DEFAULT 20,
    context_max_lookback_hours INT NOT NULL DEFAULT 72,
    context_max_chars INT NOT NULL DEFAULT 8000,
    daily_request_cap INT NOT NULL DEFAULT 200,
    monthly_request_cap INT NOT NULL DEFAULT 3000,
    monthly_token_cap BIGINT NOT NULL DEFAULT 5000000,
    notify_threshold_percent INT NOT NULL DEFAULT 80,
    reply_suggestion_enabled BIT(1) NOT NULL DEFAULT b'1',
    message_improvement_enabled BIT(1) NOT NULL DEFAULT b'1',
    translation_enabled BIT(1) NOT NULL DEFAULT b'1',
    conversion_scoring_enabled BIT(1) NOT NULL DEFAULT b'1',
    version BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_branch_settings_branch FOREIGN KEY (branch_id) REFERENCES branches(id),
    CONSTRAINT fk_ai_branch_settings_company FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT uq_ai_branch_settings_branch UNIQUE (branch_id)
);
