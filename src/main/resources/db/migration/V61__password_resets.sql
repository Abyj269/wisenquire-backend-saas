-- Module 02 P2: self-service password reset. Only a SHA-256 hash of each emailed token is stored; a token is single
-- use and expires after 30 minutes (app.security.password-reset.expiry-minutes). Additive only.
CREATE TABLE password_resets (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    token_hash  CHAR(64)    NOT NULL,
    expires_at  DATETIME(6) NOT NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    used_at     DATETIME(6) NULL,
    ip          VARCHAR(64) NULL,
    CONSTRAINT uk_password_reset_hash UNIQUE (token_hash),
    CONSTRAINT fk_password_reset_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX idx_password_reset_user ON password_resets (user_id);
