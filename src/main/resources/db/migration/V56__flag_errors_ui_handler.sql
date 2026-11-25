-- 01-foundation Part B, Step 5.3 (frontend D12 error UI). Default OFF = today's UI.
INSERT INTO feature_flags (flag_key, enabled_default, description, owner, expires_at) VALUES
('errors.ui_handler', 0, 'Step 5.3: global D12 error UI in the frontend (403 toast, Retry-After countdown, tenant-state pages, upgrade modal). Off: pages show the message as before.', 'module-01', '2027-06-30');
