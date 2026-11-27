-- Module 02 P2 (D11): rotating refresh tokens, used only when flag auth.refresh_tokens is ON for the user's org.
-- Only a SHA-256 hash of each token is stored. A family is one login; each refresh replaces the token with a new one
-- in the same family, and reusing a replaced token revokes the whole family (theft detection). Additive only.
CREATE TABLE refresh_tokens (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    family_id       VARCHAR(36)  NOT NULL,
    token_hash      CHAR(64)     NOT NULL,
    session_version BIGINT       NOT NULL,
    expires_at      DATETIME(6)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    revoked_at      DATETIME(6)  NULL,
    replaced_by     BIGINT       NULL,
    ip              VARCHAR(64)  NULL,
    user_agent      VARCHAR(255) NULL,
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX idx_refresh_token_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_token_user ON refresh_tokens (user_id);
