ALTER TABLE automations
  ADD COLUMN priority INT NOT NULL DEFAULT 100,
  ADD COLUMN stop_on_match BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE company_lead_settings
  ADD COLUMN automation_conflict_strategy VARCHAR(30) NOT NULL DEFAULT 'FIRST_MATCH';
