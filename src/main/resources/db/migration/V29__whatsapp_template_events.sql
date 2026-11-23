ALTER TABLE whatsapp_messages ADD COLUMN template_category VARCHAR(30) NULL, ADD COLUMN template_language VARCHAR(20) NULL;

CREATE TABLE whatsapp_template_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    waba_id VARCHAR(100) NOT NULL,
    template_id VARCHAR(100) NOT NULL,
    event_field VARCHAR(80) NOT NULL,
    event_epoch BIGINT NOT NULL,
    event_hash CHAR(64) NOT NULL,
    event_json TEXT NOT NULL,
    received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_template_event_hash (event_hash),
    KEY idx_template_event_latest (waba_id, event_field, template_id, event_epoch)
);
