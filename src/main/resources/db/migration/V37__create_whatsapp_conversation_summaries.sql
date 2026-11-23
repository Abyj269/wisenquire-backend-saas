-- On-demand AI summary of a conversation thread, 1:1 per conversation.
-- Refreshable (unlike the translation cache) since a conversation's content
-- keeps growing — re-summarizing overwrites the previous row.
CREATE TABLE whatsapp_conversation_summaries (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    summary_text TEXT NOT NULL,
    based_on_message_count INT NOT NULL,
    provider VARCHAR(20) NULL,
    model_name VARCHAR(100) NULL,
    generated_by_user_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_wa_conv_summaries_conversation FOREIGN KEY (conversation_id) REFERENCES whatsapp_conversations(id),
    CONSTRAINT fk_wa_conv_summaries_company FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT fk_wa_conv_summaries_user FOREIGN KEY (generated_by_user_id) REFERENCES users(id),
    CONSTRAINT uq_whatsapp_conversation_summaries_conversation UNIQUE (conversation_id)
);
