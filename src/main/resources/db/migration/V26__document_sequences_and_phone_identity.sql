CREATE TABLE document_sequences (
 company_id BIGINT NOT NULL,
 document_type VARCHAR(2) NOT NULL,
 document_year INT NOT NULL,
 next_value BIGINT NOT NULL,
 PRIMARY KEY(company_id,document_type,document_year)
);
-- Preserve all records. No automatic merging of historical suffix matches.
ALTER TABLE customers MODIFY mobile_normalized VARCHAR(50) NOT NULL;
-- Prefix historical keys so new complete-number identities never collide with a suffix.
UPDATE customers SET mobile_normalized = CONCAT('legacy-',id);
CREATE INDEX idx_leads_company_branch_created ON leads(company_id,branch_id,created_at);
CREATE INDEX idx_quotes_company_created ON quotations(company_id,created_at);
