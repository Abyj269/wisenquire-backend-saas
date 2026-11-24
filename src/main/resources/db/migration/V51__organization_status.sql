-- 01-foundation Part B item 2 (D12 tenant-state codes). Expand only: is_active stays.
-- Every existing organization becomes ACTIVE, so nothing is blocked until a later module
-- (21 platform admin, 22 billing, 20 signup) sets another status.
-- status_changed_at (not in the doc's list) is what the PAST_DUE grace period is measured from.
ALTER TABLE organizations
    ADD COLUMN status ENUM('TRIAL','ACTIVE','PAST_DUE','SUSPENDED','CANCELLED') NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN status_changed_at DATETIME(6) NULL,
    ADD COLUMN trial_ends_at DATETIME(6) NULL,
    ADD COLUMN owner_user_id BIGINT NULL,
    ADD COLUMN deleted_at DATETIME(6) NULL,
    ADD CONSTRAINT fk_organizations_owner_user FOREIGN KEY (owner_user_id) REFERENCES users (id);
