-- Module 02 P2 (Step 6.3): Content-Security-Policy violation reports, aggregated (one row per directive, blocked
-- origin, page path and disposition). Filled by POST /public/csp-report. Only origins and paths are kept: never
-- query strings, fragments or full URLs. Additive only.
CREATE TABLE csp_reports (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    directive     VARCHAR(64)  NOT NULL,
    blocked       VARCHAR(200) NOT NULL,
    document_path VARCHAR(200) NOT NULL,
    disposition   VARCHAR(10)  NOT NULL,
    hits          BIGINT       NOT NULL DEFAULT 1,
    first_seen    DATETIME(6)  NOT NULL,
    last_seen     DATETIME(6)  NOT NULL,
    UNIQUE KEY uk_csp_reports (directive, blocked, document_path, disposition)
);
