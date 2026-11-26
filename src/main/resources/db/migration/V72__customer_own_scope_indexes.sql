-- Module 07 P1: indexes for the OWN customer visibility checks (EXISTS on leads / chats of a customer).
-- Additive only. The single-column FK indexes stay.
CREATE INDEX idx_leads_customer_assignee ON leads (customer_id, assigned_to_id);
CREATE INDEX idx_conversations_agent_lead ON whatsapp_conversations (assigned_agent_id, lead_id);
