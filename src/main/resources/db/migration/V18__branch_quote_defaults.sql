-- Per-branch defaults a new quotation seeds itself with (currency, VAT %) —
-- always overridable on the quotation itself, never enforced.
ALTER TABLE branches
    ADD COLUMN default_currency VARCHAR(10) NULL,
    ADD COLUMN default_vat_percent DECIMAL(5,2) NULL;
