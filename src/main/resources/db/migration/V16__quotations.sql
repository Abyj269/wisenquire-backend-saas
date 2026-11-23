-- Basic quotation module, tied to a lead (one lead can have several
-- quotations over time). Line items snapshot their unit price/discount/tax
-- at add-time — like lead_products already does — so an edited product
-- catalog entry never silently changes a quotation that already went out.
CREATE TABLE quotations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    lead_id BIGINT NOT NULL,
    quotation_number VARCHAR(50) NOT NULL UNIQUE,
    customer_name VARCHAR(150),
    customer_phone VARCHAR(50),
    customer_email VARCHAR(150),
    customer_company_name VARCHAR(150),
    customer_tax_number VARCHAR(50),
    notes TEXT,
    subtotal DECIMAL(14,2) NOT NULL DEFAULT 0,
    total_discount DECIMAL(14,2) NOT NULL DEFAULT 0,
    total_tax DECIMAL(14,2) NOT NULL DEFAULT 0,
    grand_total DECIMAL(14,2) NOT NULL DEFAULT 0,
    currency VARCHAR(10) NOT NULL DEFAULT 'USD',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_by_id BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_quotation_organization FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_quotation_company FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT fk_quotation_lead FOREIGN KEY (lead_id) REFERENCES leads(id),
    CONSTRAINT fk_quotation_created_by FOREIGN KEY (created_by_id) REFERENCES users(id)
);

CREATE INDEX idx_quotation_lead ON quotations(lead_id);
CREATE INDEX idx_quotation_company ON quotations(company_id);

CREATE TABLE quotation_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    quotation_id BIGINT NOT NULL,
    product_id BIGINT NULL,
    item_name VARCHAR(200) NOT NULL,
    item_description TEXT,
    quantity INT NOT NULL DEFAULT 1,
    unit_price DECIMAL(14,2) NOT NULL DEFAULT 0,
    discount_percent DECIMAL(5,2) NOT NULL DEFAULT 0,
    discount_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    tax_percent DECIMAL(5,2) NOT NULL DEFAULT 0,
    tax_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    line_total DECIMAL(14,2) NOT NULL DEFAULT 0,
    sort_order INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_quotation_item_quotation FOREIGN KEY (quotation_id) REFERENCES quotations(id) ON DELETE CASCADE,
    CONSTRAINT fk_quotation_item_product FOREIGN KEY (product_id) REFERENCES product_masters(id)
);

CREATE INDEX idx_quotation_item_quotation ON quotation_items(quotation_id);
