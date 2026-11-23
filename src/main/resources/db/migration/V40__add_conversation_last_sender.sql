-- Tracks which agent most recently sent an outbound message in each
-- conversation - shown in the WhatsApp Inbox list as "last replied by",
-- distinct from the linked lead's assigned owner (any agent with access can
-- reply, not just the lead's formal owner). Only ever set by a real agent
-- send (WhatsAppCloudService.sendMessage/sendMediaMessage), never by
-- automated auto-replies, so it always names an actual person. Left
-- unchanged when the customer sends a new inbound message - it answers "who
-- has been handling this customer", not "who owes the next reply".
ALTER TABLE whatsapp_conversations ADD COLUMN last_message_sent_by_user_id BIGINT NULL;

ALTER TABLE whatsapp_conversations
    ADD CONSTRAINT fk_conv_last_sender FOREIGN KEY (last_message_sent_by_user_id) REFERENCES users (id);
