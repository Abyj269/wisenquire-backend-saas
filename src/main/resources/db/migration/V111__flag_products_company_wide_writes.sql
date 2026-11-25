-- Module 05 P1 (F-12). Default OFF = today's behaviour (any user with PRODUCT_CATALOG:ADD/EDIT/DELETE
-- on the company, including branch-level roles, may change the company's product catalogue).
-- ON for an org: product create / update / delete need company-wide scope (COMPANY level or above).
INSERT INTO feature_flags (flag_key, enabled_default, description, owner, expires_at) VALUES
('products.company_wide_writes', 0, 'F-12: product catalogue writes need company-wide scope (COMPANY level or above). Off: any user with PRODUCT_CATALOG rights on the company, as before.', 'module-05', '2027-12-31');
