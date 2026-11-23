-- One nullable column: the message's detected language (ISO 639-1), set
-- once at inbound-webhook ingestion time via a free local heuristic (no AI
-- call) for direction=INBOUND + messageType=TEXT only. NULL = never
-- detected, including every historical row (no backfill). Drives the chat
-- UI's translate-icon visibility.
ALTER TABLE whatsapp_messages ADD COLUMN detected_language VARCHAR(10) NULL;
