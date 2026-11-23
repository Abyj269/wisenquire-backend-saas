-- Captured when staff pick the delivery address from the map picker (Google
-- Places) while scheduling — nullable since manual address entry without a
-- map selection remains fully supported.
ALTER TABLE deliveries
    ADD COLUMN latitude DECIMAL(10,7) NULL,
    ADD COLUMN longitude DECIMAL(10,7) NULL;
