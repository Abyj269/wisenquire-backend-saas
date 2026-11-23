-- Nationality/Preferred Language: fixed option lists (not admin-editable via
-- DynamicFieldDefinition), so they're plain native columns instead.
ALTER TABLE leads
    ADD COLUMN nationality VARCHAR(2) NULL AFTER job_title,
    ADD COLUMN preferred_language VARCHAR(5) NULL AFTER job_title;
