-- Cached on-demand transcription of an inbound AUDIO (voice note) message,
-- 1:1 per message, same shape/immutability rationale as
-- whatsapp_message_translations. Scoped to OpenAI-only initially at the
-- application layer (provider support is uneven across vendors).
CREATE TABLE whatsapp_message_transcriptions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    transcript_text TEXT NOT NULL,
    provider VARCHAR(20) NULL,
    model_name VARCHAR(100) NULL,
    generated_by_user_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_wa_msg_transcriptions_message FOREIGN KEY (message_id) REFERENCES whatsapp_messages(id),
    CONSTRAINT fk_wa_msg_transcriptions_company FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT fk_wa_msg_transcriptions_user FOREIGN KEY (generated_by_user_id) REFERENCES users(id),
    CONSTRAINT uq_whatsapp_message_transcriptions_message UNIQUE (message_id)
);
