CREATE TABLE whatsapp_saved_replies (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    body VARCHAR(4096) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_saved_reply_company FOREIGN KEY (company_id) REFERENCES companies(id),
    INDEX idx_saved_reply_company_title (company_id, title)
);
ALTER TABLE whatsapp_conversations
    ADD COLUMN pinned BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN manually_unread BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_conv_company_pin_lastmsg ON whatsapp_conversations(company_id, pinned, last_message_at, id);
