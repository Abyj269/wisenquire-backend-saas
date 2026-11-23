-- Meta's wamid format has grown longer on newer Cloud API versions and can
-- run close to the old varchar(150) cap. A status-update webhook's id is
-- matched against this column by exact string equality (see
-- WhatsAppCloudService.processMessageStatus) — a silently truncated wamid at
-- send time would make every later delivered/read receipt fail to match,
-- leaving the message stuck on its initial SENT tick with no error anywhere.
-- Widen it well past any known real-world length as a safety margin.
ALTER TABLE whatsapp_messages MODIFY COLUMN wamid varchar(255);
