-- Per-branch letterhead details for the quotation PDF — each optional and
-- falling back to the company's own value where one exists (logo, TRN);
-- address and quotation terms have no company-level equivalent to fall
-- back to, so they're simply blank on the PDF until set here.
ALTER TABLE branches
    ADD COLUMN logo_url TEXT NULL,
    ADD COLUMN address VARCHAR(255) NULL,
    ADD COLUMN trn_number VARCHAR(50) NULL,
    ADD COLUMN quotation_terms TEXT NULL;
