-- Per-company hide/mandatory config for native (predefined) Lead form
-- fields. Closed set of field_key values (see PredefinedLeadField enum) —
-- no admin-add/delete, only 12 rows ever exist per company, auto-seeded on
-- first read (see PredefinedFieldService.getConfigsByCompany).
CREATE TABLE predefined_field_configs (
    id bigint NOT NULL AUTO_INCREMENT,
    company_id bigint NOT NULL,
    field_key varchar(40) NOT NULL,
    is_visible bit(1) NOT NULL DEFAULT b'1',
    is_mandatory bit(1) NOT NULL DEFAULT b'0',
    created_at datetime(6),
    updated_at datetime(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_predefined_field_company_key (company_id, field_key)
) ENGINE=InnoDB;
