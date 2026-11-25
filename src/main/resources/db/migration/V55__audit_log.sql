-- 01-foundation Part B item 9: who changed what, for admin and settings actions.
-- Written by the @Audited interceptor after each POST/PUT/PATCH/DELETE on an annotated controller.
-- Secrets in request bodies are masked before they are stored.
CREATE TABLE audit_log (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT       NULL,
    actor_user_id    BIGINT       NULL,
    impersonated_by  BIGINT       NULL,
    action           VARCHAR(200) NOT NULL,
    entity_type      VARCHAR(60)  NOT NULL,
    entity_id        VARCHAR(64)  NULL,
    before_json      LONGTEXT     NULL,
    after_json       LONGTEXT     NULL,
    outcome          ENUM('SUCCESS','DENIED','FAILED') NOT NULL,
    http_status      INT          NOT NULL,
    ip               VARCHAR(64)  NULL,
    trace_id         VARCHAR(64)  NULL,
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_audit_org_time (organization_id, created_at),
    KEY idx_audit_actor_time (actor_user_id, created_at),
    KEY idx_audit_entity (entity_type, entity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
