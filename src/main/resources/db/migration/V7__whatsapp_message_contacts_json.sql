-- A "contacts" message can carry several vCards (name + phone numbers) — the
-- old bodyText-only handling flattened them into a single comma-joined name
-- string with no phone numbers at all. This column stores the full
-- structured list as JSON so the UI can render one card per shared contact.
ALTER TABLE whatsapp_messages
    ADD COLUMN contacts_json TEXT;
