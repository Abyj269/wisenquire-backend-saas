-- Meta App ID per connected number, needed for the Resumable Upload API
-- (/{app-id}/uploads) that template Image/Video/Document headers and
-- Carousel cards require to obtain a header_handle for their sample media.
ALTER TABLE whatsapp_configurations
    ADD COLUMN app_id varchar(100);

-- Which template a TEMPLATE-type outbound message used — lets the template
-- health dashboard count real usage per template/date-range instead of
-- guessing from body text.
ALTER TABLE whatsapp_messages
    ADD COLUMN template_name varchar(512);
