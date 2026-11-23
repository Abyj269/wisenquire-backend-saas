-- Mirrors WhatsAppMessage.ctwaClid onto the conversation (from the first
-- ad-referral message it ever received), the same way is_from_ad/
-- ad_headline/ad_source_url already are, so lead-creation code can read it
-- straight off the conversation instead of re-querying its messages.
-- Unlike ad_source_id, this is NOT gated to "ad"-only referrals — ctwa_clid
-- is meaningful for both "ad" and "post" referral types.
ALTER TABLE whatsapp_conversations ADD COLUMN ctwa_clid VARCHAR(255) NULL;
