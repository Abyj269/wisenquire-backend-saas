CREATE TABLE broadcasts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version BIGINT,
    company_id BIGINT NOT NULL,
    branch_id BIGINT,
    whatsapp_configuration_id BIGINT,
    created_by_user_id BIGINT,
    name VARCHAR(200) NOT NULL,
    template_name VARCHAR(150) NOT NULL,
    template_language VARCHAR(20),
    template_components_json LONGTEXT,
    audience_type VARCHAR(50) NOT NULL,
    audience_filter_json LONGTEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    scheduled_at DATETIME(6),
    sent_at DATETIME(6),
    completed_at DATETIME(6),
    total_recipients INT DEFAULT 0,
    sent_count INT DEFAULT 0,
    delivered_count INT DEFAULT 0,
    read_count INT DEFAULT 0,
    failed_count INT DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6),
    deleted_at DATETIME(6),
    CONSTRAINT fk_broadcast_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_broadcast_branch FOREIGN KEY (branch_id) REFERENCES branches (id),
    CONSTRAINT fk_broadcast_config FOREIGN KEY (whatsapp_configuration_id) REFERENCES whatsapp_configurations (id),
    CONSTRAINT fk_broadcast_user FOREIGN KEY (created_by_user_id) REFERENCES users (id)
);

CREATE INDEX idx_broadcast_company_branch ON broadcasts(company_id, branch_id);
CREATE INDEX idx_broadcast_status ON broadcasts(status);
CREATE INDEX idx_broadcast_scheduled ON broadcasts(scheduled_at);

CREATE TABLE broadcast_recipients (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    broadcast_id BIGINT NOT NULL,
    customer_id BIGINT,
    lead_id BIGINT,
    recipient_phone VARCHAR(50) NOT NULL,
    recipient_name VARCHAR(150),
    wamid VARCHAR(255),
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    error_message VARCHAR(500),
    sent_at DATETIME(6),
    delivered_at DATETIME(6),
    read_at DATETIME(6),
    failed_at DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_bcast_recip_broadcast FOREIGN KEY (broadcast_id) REFERENCES broadcasts (id) ON DELETE CASCADE,
    CONSTRAINT fk_bcast_recip_customer FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE SET NULL,
    CONSTRAINT fk_bcast_recip_lead FOREIGN KEY (lead_id) REFERENCES leads (id) ON DELETE SET NULL
);

CREATE INDEX idx_bcast_recip_broadcast ON broadcast_recipients(broadcast_id);
CREATE INDEX idx_bcast_recip_wamid ON broadcast_recipients(wamid);
CREATE INDEX idx_bcast_recip_status ON broadcast_recipients(status);
