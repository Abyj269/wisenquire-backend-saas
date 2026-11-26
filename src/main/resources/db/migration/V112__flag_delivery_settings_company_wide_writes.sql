-- Module 09 P1 (F-12). Default OFF = today's behaviour (any user with delivery-settings rights on the
-- company, including branch-level roles, may change failure reasons and company delivery settings).
-- ON for an org: those writes need company-wide scope (COMPANY level or above). Reads are unchanged.
INSERT INTO feature_flags (flag_key, enabled_default, description, owner, expires_at) VALUES
('delivery_settings.company_wide_writes', 0, 'F-12: delivery failure reasons and company delivery settings writes need company-wide scope (COMPANY level or above). Off: as before.', 'module-09', '2027-12-31');
