-- Cached on-demand English translation of one inbound message, 1:1 via a
-- unique FK. A separate table rather than inline columns on
-- whatsapp_messages because translations are sparse (most messages are
-- agent-authored, already English, or non-text). Immutable — messages never
-- change, so a translation is never regenerated (no updated_at).
CREATE TABLE whatsapp_message_translations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    source_language VARCHAR(10) NOT NULL,
    translated_text TEXT NOT NULL,
    provider VARCHAR(20) NULL,
    model_name VARCHAR(100) NULL,
    generated_by_user_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_wa_msg_translations_message FOREIGN KEY (message_id) REFERENCES whatsapp_messages(id),
    CONSTRAINT fk_wa_msg_translations_company FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT fk_wa_msg_translations_user FOREIGN KEY (generated_by_user_id) REFERENCES users(id),
    CONSTRAINT uq_whatsapp_message_translations_message UNIQUE (message_id)
);
