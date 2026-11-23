-- Company-configurable master list of "why delivery failed" reasons — mirrors lead_lost_reasons.
CREATE TABLE delivery_failure_reasons (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NULL,
    reason_text VARCHAR(255) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_delivery_failure_reason_company FOREIGN KEY (company_id) REFERENCES companies(id)
);
CREATE INDEX idx_delivery_failure_reason_company ON delivery_failure_reasons(company_id, sort_order);

-- One row per company — the Delivery module's master toggle + approval-requirement flags.
CREATE TABLE company_delivery_settings (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    delivery_module_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    require_approval_for_postpone BOOLEAN NOT NULL DEFAULT TRUE,
    require_approval_for_close BOOLEAN NOT NULL DEFAULT TRUE,
    max_delivery_attempts INT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uq_company_delivery_settings_company UNIQUE (company_id),
    CONSTRAINT fk_company_delivery_settings_company FOREIGN KEY (company_id) REFERENCES companies(id)
);

-- The delivery itself: one row per scheduled delivery attempt-cycle against a Lead.
CREATE TABLE deliveries (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    organization_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    lead_id BIGINT NOT NULL,
    delivery_number VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL,
    scheduled_date DATE NOT NULL,
    assigned_to_id BIGINT NULL,
    assigned_by_id BIGINT NULL,
    recipient_name VARCHAR(150) NOT NULL,
    recipient_phone VARCHAR(50) NOT NULL,
    address_line1 VARCHAR(255) NOT NULL,
    address_line2 VARCHAR(255) NULL,
    city VARCHAR(100) NULL,
    state_or_emirate VARCHAR(100) NULL,
    country VARCHAR(100) NULL,
    postal_code VARCHAR(20) NULL,
    landmark VARCHAR(255) NULL,
    delivery_notes TEXT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_attempt_at DATETIME NULL,
    delivered_at DATETIME NULL,
    delivery_proof_notes TEXT NULL,
    failure_reason_id BIGINT NULL,
    failure_notes TEXT NULL,
    requested_action VARCHAR(20) NOT NULL DEFAULT 'NONE',
    requested_new_date DATE NULL,
    requested_by_user_id BIGINT NULL,
    requested_at DATETIME NULL,
    request_notes TEXT NULL,
    approval_decision_by_id BIGINT NULL,
    approval_decision_at DATETIME NULL,
    approval_rejection_notes TEXT NULL,
    cancelled_reason TEXT NULL,
    cancelled_by_id BIGINT NULL,
    cancelled_at DATETIME NULL,
    created_by_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uq_delivery_number UNIQUE (delivery_number),
    CONSTRAINT fk_delivery_organization FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_delivery_company FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT fk_delivery_branch FOREIGN KEY (branch_id) REFERENCES branches(id),
    CONSTRAINT fk_delivery_lead FOREIGN KEY (lead_id) REFERENCES leads(id),
    CONSTRAINT fk_delivery_assigned_to FOREIGN KEY (assigned_to_id) REFERENCES users(id),
    CONSTRAINT fk_delivery_assigned_by FOREIGN KEY (assigned_by_id) REFERENCES users(id),
    CONSTRAINT fk_delivery_failure_reason FOREIGN KEY (failure_reason_id) REFERENCES delivery_failure_reasons(id),
    CONSTRAINT fk_delivery_requested_by FOREIGN KEY (requested_by_user_id) REFERENCES users(id),
    CONSTRAINT fk_delivery_approval_by FOREIGN KEY (approval_decision_by_id) REFERENCES users(id),
    CONSTRAINT fk_delivery_cancelled_by FOREIGN KEY (cancelled_by_id) REFERENCES users(id),
    CONSTRAINT fk_delivery_created_by FOREIGN KEY (created_by_id) REFERENCES users(id)
);

-- Agent day-view: "my deliveries for date X", company-scoped.
CREATE INDEX idx_delivery_company_assigned_date ON deliveries(company_id, assigned_to_id, scheduled_date);
-- Branch-scoped admin grids.
CREATE INDEX idx_delivery_branch_date ON deliveries(branch_id, scheduled_date);
-- Approvals queue filter.
CREATE INDEX idx_delivery_company_status ON deliveries(company_id, status);
-- Lead Detail "Deliveries" tab.
CREATE INDEX idx_delivery_lead ON deliveries(lead_id);

-- Let delivery lifecycle events show up in the existing Lead timeline, and be
-- queried per-delivery (a Lead can accumulate more than one Delivery over time).
ALTER TABLE lead_timeline_activities
    ADD COLUMN delivery_id BIGINT NULL,
    ADD CONSTRAINT fk_timeline_delivery FOREIGN KEY (delivery_id) REFERENCES deliveries(id);
CREATE INDEX idx_timeline_delivery ON lead_timeline_activities(delivery_id, created_at);
