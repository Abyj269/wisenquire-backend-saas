-- TEXT caps out at 65,535 bytes — a base64-encoded logo anywhere near the
-- 400KB client-side upload limit blows past that and fails to save.
-- LONGTEXT removes that ceiling in practice (up to 4GB).
ALTER TABLE organizations MODIFY COLUMN logo_url LONGTEXT NULL;
ALTER TABLE companies MODIFY COLUMN logo_url LONGTEXT NULL;
ALTER TABLE branches MODIFY COLUMN logo_url LONGTEXT NULL;
