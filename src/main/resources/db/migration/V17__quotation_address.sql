ALTER TABLE quotations
    ADD COLUMN customer_address VARCHAR(255) NULL AFTER customer_tax_number;
