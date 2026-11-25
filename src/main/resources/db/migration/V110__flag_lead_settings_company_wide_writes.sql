-- Module 04 P1 (F-12). Default OFF = today's behaviour (any user with LEAD_SETTINGS:ADD/EDIT/DELETE
-- on the company, including branch-level roles, may change the company's lead settings).
-- ON for an org: statuses, purposes, lost reasons, sources, dynamic fields, predefined field configs
-- and company lead settings can only be changed with company-wide scope (COMPANY level or above).
INSERT INTO feature_flags (flag_key, enabled_default, description, owner, expires_at) VALUES
('lead_settings.company_wide_writes', 0, 'F-12: lead settings writes need company-wide scope (COMPANY level or above). Off: any user with LEAD_SETTINGS rights on the company, as before.', 'module-04', '2027-12-31');
