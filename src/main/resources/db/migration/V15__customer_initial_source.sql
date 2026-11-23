ALTER TABLE customers
    ADD COLUMN initial_source_id BIGINT NULL,
    ADD CONSTRAINT fk_customer_initial_source FOREIGN KEY (initial_source_id) REFERENCES lead_sources(id);
