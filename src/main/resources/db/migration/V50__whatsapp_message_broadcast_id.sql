-- Adds a nullable broadcast_id to whatsapp_messages so that template messages
-- sent as part of a broadcast campaign can be traced back to the originating
-- broadcast in the chat UI (agents see a "Broadcast" badge, audit logs can
-- correlate messages to campaigns).
--
-- broadcast_id is NULL for all regular chat messages (agent-sent or inbound).
-- It is set only by BroadcastServiceImpl.executeBroadcastSend() when it creates
-- a WhatsAppMessage record after dispatching to Meta.

ALTER TABLE whatsapp_messages ADD COLUMN broadcast_id BIGINT NULL;

ALTER TABLE whatsapp_messages
    ADD CONSTRAINT fk_msg_broadcast
    FOREIGN KEY (broadcast_id) REFERENCES broadcasts(id) ON DELETE SET NULL;

CREATE INDEX idx_msg_broadcast ON whatsapp_messages(broadcast_id);
