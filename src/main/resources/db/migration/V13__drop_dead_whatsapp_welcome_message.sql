-- Removes the old company-wide "WhatsApp Auto-Reply" welcome-message toggle
-- (Settings page) — it was never actually wired up to send anything, and is
-- now superseded by the real, branch-wise auto-reply system (working hours /
-- non-working hours / holidays) on the Business Hours & Holidays page.
ALTER TABLE company_lead_settings
    DROP COLUMN whatsapp_auto_reply_enabled,
    DROP COLUMN whatsapp_welcome_message;
