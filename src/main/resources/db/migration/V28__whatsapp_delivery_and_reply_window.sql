ALTER TABLE whatsapp_conversations ADD COLUMN last_customer_message_epoch BIGINT NULL;
ALTER TABLE whatsapp_messages MODIFY COLUMN status ENUM ('ACCEPTED','DELIVERED','FAILED','READ','SENT','UNKNOWN') NOT NULL;
ALTER TABLE whatsapp_messages ADD COLUMN client_message_id VARCHAR(100) NULL;
CREATE UNIQUE INDEX uk_wa_client_message ON whatsapp_messages(conversation_id, client_message_id);
-- Older SENT rows were assigned from HTTP success, not confirmed delivery receipts.
-- Keep that uncertainty visible. Do not infer a customer window from received_at.
UPDATE whatsapp_messages SET status = 'UNKNOWN' WHERE direction = 'OUTBOUND' AND status = 'SENT';

CREATE TABLE whatsapp_status_receipts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    phone_number_id VARCHAR(100) NOT NULL,
    wamid VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    occurred_at BIGINT NOT NULL,
    error_code INT NULL,
    error_detail VARCHAR(255) NULL,
    applied BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_wa_receipt (phone_number_id, wamid, status, occurred_at),
    KEY idx_wa_receipt_pending (applied, id)
);
