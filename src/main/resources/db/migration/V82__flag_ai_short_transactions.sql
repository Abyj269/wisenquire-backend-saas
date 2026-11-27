-- Module 18 P1 (S3-b). Default OFF = today's behaviour (each AI feature runs in one transaction that holds the
-- branch's usage-counter row lock during the provider call, so a branch's AI calls run one after another).
-- ON for an org: short transactions; no transaction or counter lock is open during the provider call.
INSERT INTO feature_flags (flag_key, enabled_default, description, owner, expires_at) VALUES
('ai.short_transactions', 0, 'S3-b: AI calls run with no DB transaction or usage-counter lock open during the provider call. Off: as before.', 'module-18', '2027-06-30');
