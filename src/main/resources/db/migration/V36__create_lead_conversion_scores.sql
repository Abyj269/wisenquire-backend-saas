-- Cached AI conversion-strength score per lead (current value only — refresh
-- history is logged to the existing lead_timeline_activities table instead
-- of a new history table). Refresh is manual/on-demand only, never a
-- scheduled batch job. urgency_label is piggybacked from the same AI call
-- that produces the score (zero extra provider calls).
CREATE TABLE lead_conversion_scores (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lead_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    score INT NOT NULL,
    strength_label VARCHAR(20) NOT NULL,
    urgency_label VARCHAR(20) NULL,
    rationale TEXT NOT NULL,
    signals_json TEXT NULL,
    based_on_message_count INT NOT NULL,
    based_on_conversation_id BIGINT NULL,
    provider VARCHAR(20) NULL,
    model_name VARCHAR(100) NULL,
    generated_by_user_id BIGINT NOT NULL,
    generated_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_lead_conversion_scores_lead FOREIGN KEY (lead_id) REFERENCES leads(id),
    CONSTRAINT fk_lead_conversion_scores_company FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT fk_lead_conversion_scores_conversation FOREIGN KEY (based_on_conversation_id) REFERENCES whatsapp_conversations(id),
    CONSTRAINT fk_lead_conversion_scores_user FOREIGN KEY (generated_by_user_id) REFERENCES users(id),
    CONSTRAINT uq_lead_conversion_scores_lead UNIQUE (lead_id)
);
