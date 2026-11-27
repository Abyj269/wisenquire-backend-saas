-- Module 02 P2 item 4: transactional email outbox. An email is written in the same transaction as the change that
-- causes it, and the email-outbox job (ShedLock) sends it later with no transaction open, retrying with backoff.
-- Additive only.
CREATE TABLE email_outbox (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    to_address      VARCHAR(320) NOT NULL,
    template        VARCHAR(40)  NOT NULL,
    variables_json  TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6)  NOT NULL,
    claimed_at      DATETIME(6)  NULL,
    sent_at         DATETIME(6)  NULL,
    last_error      VARCHAR(500) NULL,
    created_at      DATETIME(6)  NOT NULL
);
CREATE INDEX idx_email_outbox_due ON email_outbox (status, next_attempt_at);
