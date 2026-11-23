-- Durable, atomically-incremented AI usage counters, modeled on
-- document_sequences — deliberately NOT the in-memory RequestRateLimiter,
-- which resets on restart and isn't safe across multiple app instances.
-- One row per (branch, period_type, period_key); period_key is computed in
-- company-local time ("YYYY-MM-DD" for DAY, "YYYY-MM" for MONTH).
CREATE TABLE ai_usage_counters (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    branch_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    period_type VARCHAR(10) NOT NULL,
    period_key VARCHAR(10) NOT NULL,
    request_count BIGINT NOT NULL DEFAULT 0,
    estimated_tokens BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uq_ai_usage_counters_branch_period UNIQUE (branch_id, period_type, period_key)
);
