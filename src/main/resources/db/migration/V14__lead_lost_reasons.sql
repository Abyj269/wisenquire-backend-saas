-- Company-configurable list of "why was this lead lost" reasons, managed in
-- Settings the same way Lead Purposes/Sources already are — no system-locked
-- rows, fully editable/deletable per company.
CREATE TABLE lead_lost_reasons (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT,
    reason_text VARCHAR(255) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    is_active BIT(1) NOT NULL DEFAULT b'1',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_llr_company FOREIGN KEY (company_id) REFERENCES companies(id)
);

-- Recorded when a lead is marked Lost via the dedicated markLeadLost flow
-- (mirrors isConverted/convertedAt's role for the Convert flow).
ALTER TABLE leads
    ADD COLUMN lost_reason_id BIGINT NULL,
    ADD COLUMN lost_notes TEXT NULL,
    ADD CONSTRAINT fk_lead_lost_reason FOREIGN KEY (lost_reason_id) REFERENCES lead_lost_reasons(id);
