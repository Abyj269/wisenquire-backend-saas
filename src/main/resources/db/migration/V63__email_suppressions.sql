-- Module 02 P2 item 4: addresses that hard-bounced or complained. The outbox never sends to them (status SUPPRESSED).
-- Filled by the signed POST /email/webhook/bounce. Additive only.
CREATE TABLE email_suppressions (
    address    VARCHAR(320) NOT NULL PRIMARY KEY,
    reason     VARCHAR(20)  NOT NULL,
    source     VARCHAR(60)  NULL,
    created_at DATETIME(6)  NOT NULL
);
