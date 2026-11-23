-- Customer blocking (CRM chat "Block Customer" action). A blocked customer's
-- WhatsApp conversation drops out of the inbox list entirely (see
-- WhatsAppCloudService.buildConversationSpecification) - inbound messages are
-- still stored, just not surfaced, so unblocking later restores full history.
ALTER TABLE customers
    ADD COLUMN is_blocked BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN blocked_at DATETIME(6) NULL,
    ADD COLUMN blocked_by_user_id BIGINT NULL;

ALTER TABLE customers
    ADD CONSTRAINT fk_customer_blocked_by FOREIGN KEY (blocked_by_user_id) REFERENCES users (id);

CREATE INDEX idx_customer_company_blocked ON customers (company_id, is_blocked);
