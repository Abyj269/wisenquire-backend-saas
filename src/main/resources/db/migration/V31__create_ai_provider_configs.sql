-- Per-branch AI provider credentials (OpenAI/Anthropic/Gemini/Grok). A
-- branch may hold several rows (one per provider) but only the one with
-- is_selected=1 is actually used — UNIQUE(branch_id, provider) means
-- re-adding the same provider is an edit, not a new row.
CREATE TABLE ai_provider_configs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    provider VARCHAR(20) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    api_key TEXT NOT NULL,
    api_base_url_override VARCHAR(255) NULL,
    provider_account_ref VARCHAR(150) NULL,
    is_active BIT(1) NOT NULL DEFAULT b'1',
    is_selected BIT(1) NOT NULL DEFAULT b'0',
    version BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_provider_configs_company FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT fk_ai_provider_configs_branch FOREIGN KEY (branch_id) REFERENCES branches(id),
    CONSTRAINT uq_ai_provider_configs_branch_provider UNIQUE (branch_id, provider)
);

CREATE INDEX idx_ai_provider_configs_company ON ai_provider_configs (company_id);
