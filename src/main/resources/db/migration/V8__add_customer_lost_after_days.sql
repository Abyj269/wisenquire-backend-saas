ALTER TABLE company_lead_settings
    ADD COLUMN customer_lost_after_days INT NOT NULL DEFAULT 60;
