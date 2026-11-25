-- Module 03 P1 (D15 option A). Default OFF = today's behaviour (any IANA zone accepted).
-- ON for an org: companies may only use Asia/Kolkata, Asia/Dubai, Asia/Muscat, Asia/Riyadh,
-- Asia/Qatar, Asia/Bahrain, Asia/Kuwait, and the frontend lists only those.
INSERT INTO feature_flags (flag_key, enabled_default, description, owner, expires_at) VALUES
('tenant.timezone_allowlist', 0, 'D15: company timezones limited to the 7 year-1 zones (backend validation + frontend list). Off: any zone, as before.', 'module-03', '2027-12-31');
