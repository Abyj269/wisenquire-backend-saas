-- Module 06 P1. Default OFF = today's number format LD-<companyId>-<year>-<seq>.
-- ON for an org: NEW lead, quotation and delivery numbers use the company's random code (V70):
-- LD-<code>-<year>-<seq>. Existing numbers never change.
INSERT INTO feature_flags (flag_key, enabled_default, description, owner, expires_at) VALUES
('documents.company_code_prefix', 0, 'New lead/quotation/delivery numbers carry the company random code instead of the internal company id. Off: LD-<companyId>-..., as before.', 'module-06', '2027-12-31');
