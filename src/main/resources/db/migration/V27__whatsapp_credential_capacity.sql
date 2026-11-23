-- Encryption is performed by the application using its external key, never SQL literals.
ALTER TABLE whatsapp_configurations MODIFY COLUMN access_token TEXT NOT NULL;
ALTER TABLE whatsapp_configurations MODIFY COLUMN app_secret TEXT NULL;
