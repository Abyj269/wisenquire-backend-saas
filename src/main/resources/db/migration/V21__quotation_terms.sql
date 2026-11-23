-- Distinct from `notes` (a free-text note) — this is the bulleted Terms &
-- Conditions block on the PDF, seeded from Branch.quotation_terms.
ALTER TABLE quotations
    ADD COLUMN terms_and_conditions TEXT NULL;
