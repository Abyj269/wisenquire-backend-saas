-- Full WhatsApp Cloud API message-type support: location, contacts,
-- interactive button/list replies, reactions, quoted-message context, and
-- "Click to WhatsApp Ad" referral tracking.
--
-- media_id (Meta's own id, resolved from the webhook payload) replaces
-- eager media downloads at webhook time — WhatsAppMediaService resolves and
-- caches the actual bytes lazily on first view instead of blocking webhook
-- processing (Meta expects a fast 200 OK per delivery) or downloading media
-- nobody ever opens.
ALTER TABLE whatsapp_messages
    ADD COLUMN media_id varchar(150),
    ADD COLUMN latitude double,
    ADD COLUMN longitude double,
    ADD COLUMN location_name varchar(255),
    ADD COLUMN location_address varchar(500),
    ADD COLUMN reaction_emoji varchar(20),
    ADD COLUMN reaction_target_wamid varchar(150),
    ADD COLUMN context_wamid varchar(150),
    ADD COLUMN is_from_ad bit(1) NOT NULL DEFAULT b'0',
    ADD COLUMN ad_headline varchar(255),
    ADD COLUMN ad_source_url varchar(500),
    ADD COLUMN ad_source_type varchar(30),
    ADD COLUMN ctwa_clid varchar(255);

-- Mirrored onto the conversation (from the first ad-referral message it
-- ever received) so the conversation LIST can show an ad badge without
-- opening the chat and re-scanning its messages.
ALTER TABLE whatsapp_conversations
    ADD COLUMN is_from_ad bit(1) NOT NULL DEFAULT b'0',
    ADD COLUMN ad_headline varchar(255),
    ADD COLUMN ad_source_url varchar(500);
