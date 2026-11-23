-- Branch-wise weekly working hours (one row per branch/day-of-week; a
-- branch with no rows simply never participates in working/non-working
-- auto-replies — opt-in per branch).
CREATE TABLE whatsapp_business_hours (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    branch_id BIGINT NOT NULL,
    day_of_week TINYINT NOT NULL, -- 1=Mon .. 7=Sun
    is_open BIT(1) NOT NULL DEFAULT b'1',
    open_time TIME NULL,
    close_time TIME NULL,
    UNIQUE KEY uq_branch_day (branch_id, day_of_week),
    CONSTRAINT fk_bh_branch FOREIGN KEY (branch_id) REFERENCES branches(id)
);

-- Company holiday calendar. Date-range, applies to whichever branches are
-- linked in whatsapp_holiday_branches. An optional message/media overrides
-- the company's common holiday auto-reply just for these dates.
CREATE TABLE whatsapp_holidays (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    message_text TEXT NULL,
    media_data LONGTEXT NULL,
    media_mime_type VARCHAR(100) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_holiday_company FOREIGN KEY (company_id) REFERENCES companies(id)
);

CREATE TABLE whatsapp_holiday_branches (
    holiday_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    PRIMARY KEY (holiday_id, branch_id),
    CONSTRAINT fk_hb_holiday FOREIGN KEY (holiday_id) REFERENCES whatsapp_holidays(id) ON DELETE CASCADE,
    CONSTRAINT fk_hb_branch FOREIGN KEY (branch_id) REFERENCES branches(id)
);

-- One row per company: the three common auto-reply messages (working
-- hours / non-working hours / holiday), each independently toggled and each
-- optionally carrying an image/video (base64 data URI, same convention as
-- Company.logoUrl).
CREATE TABLE whatsapp_auto_reply_settings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL UNIQUE,
    working_hours_enabled BIT(1) NOT NULL DEFAULT b'0',
    working_hours_message TEXT NULL,
    working_hours_media_data LONGTEXT NULL,
    working_hours_media_mime_type VARCHAR(100) NULL,
    non_working_hours_enabled BIT(1) NOT NULL DEFAULT b'0',
    non_working_hours_message TEXT NULL,
    non_working_hours_media_data LONGTEXT NULL,
    non_working_hours_media_mime_type VARCHAR(100) NULL,
    holiday_enabled BIT(1) NOT NULL DEFAULT b'0',
    holiday_message TEXT NULL,
    holiday_media_data LONGTEXT NULL,
    holiday_media_mime_type VARCHAR(100) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_ars_company FOREIGN KEY (company_id) REFERENCES companies(id)
);

-- Throttle: at most one auto-reply per conversation per (company-local)
-- calendar day.
ALTER TABLE whatsapp_conversations ADD COLUMN last_auto_reply_at DATETIME NULL;

-- Audit trail: which outbound messages were auto-replies vs agent-sent.
ALTER TABLE whatsapp_messages ADD COLUMN is_auto_reply BIT(1) NOT NULL DEFAULT b'0';
