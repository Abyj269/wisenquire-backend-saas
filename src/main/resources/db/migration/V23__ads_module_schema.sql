-- Ads Tracking & Attribution module: connected Meta/Google ad accounts, the
-- ads/campaigns synced from them, daily spend snapshots, and the WhatsApp/
-- Lead columns needed to attribute a lead to the specific ad it came from.

-- branch_id NULL = company-wide default connection for that provider;
-- non-null = one branch's dedicated override. Uniqueness of "at most one
-- default + one override per branch per provider" is enforced in
-- AdAccountConnectionService, not here — MySQL/MariaDB unique indexes treat
-- NULL as distinct from NULL, so a composite unique key would not actually
-- stop two company-default rows for the same provider.
--
-- access_token / google_developer_token / google_client_secret /
-- google_refresh_token are AES-256-GCM encrypted (AdCredentialEncryptor)
-- before storage, hence varchar(700) rather than the 500 WhatsAppConfiguration
-- uses for its plaintext token — encryption + base64 runs longer than the
-- raw secret.
CREATE TABLE ad_account_connections (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    branch_id BIGINT NULL,
    provider VARCHAR(20) NOT NULL,
    external_account_id VARCHAR(100) NOT NULL,
    display_name VARCHAR(150) NULL,
    is_active BIT(1) NOT NULL DEFAULT b'1',
    access_token VARCHAR(700) NULL,
    app_id VARCHAR(100) NULL,
    google_developer_token VARCHAR(700) NULL,
    google_client_id VARCHAR(255) NULL,
    google_client_secret VARCHAR(700) NULL,
    google_refresh_token VARCHAR(700) NULL,
    google_login_customer_id VARCHAR(50) NULL,
    last_sync_at DATETIME NULL,
    last_sync_status VARCHAR(20) NOT NULL DEFAULT 'NEVER_SYNCED',
    last_sync_error VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_adconn_company FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT fk_adconn_branch FOREIGN KEY (branch_id) REFERENCES branches(id)
);

CREATE INDEX idx_adconn_company_branch_provider ON ad_account_connections (company_id, branch_id, provider);

-- branch_id is a denormalized copy of connection.branch_id at sync time (not
-- a live join) so the ad-performance report can tell, per row, whether it's
-- looking at a branch-exclusive ad account or one shared company-wide.
CREATE TABLE ads (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    branch_id BIGINT NULL,
    connection_id BIGINT NOT NULL,
    provider VARCHAR(20) NOT NULL,
    external_ad_id VARCHAR(100) NOT NULL,
    external_campaign_id VARCHAR(100) NOT NULL,
    external_adset_id VARCHAR(100) NULL,
    ad_name VARCHAR(255) NULL,
    campaign_name VARCHAR(255) NULL,
    adset_name VARCHAR(255) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    objective VARCHAR(50) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_ads_company FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT fk_ads_branch FOREIGN KEY (branch_id) REFERENCES branches(id),
    CONSTRAINT fk_ads_connection FOREIGN KEY (connection_id) REFERENCES ad_account_connections(id),
    UNIQUE KEY uq_ads_connection_external (connection_id, external_ad_id)
);

CREATE INDEX idx_ads_company_campaign ON ads (company_id, external_campaign_id);

-- Daily grain, not lifetime totals or per-sync snapshots — this is what
-- makes both "spend until now" (SUM(spend), no date filter) and "spend in
-- an arbitrary date range" (+ AND stat_date BETWEEN :from AND :to) the same
-- trivial query, and what makes every sync/backfill/re-run a safe upsert on
-- (ad_id, stat_date) instead of double-counting spend. currency is the ad
-- account's own currency, stored as-is — no FX conversion to Company.currency.
CREATE TABLE ad_insights (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ad_id BIGINT NOT NULL,
    stat_date DATE NOT NULL,
    spend DECIMAL(14, 2) NOT NULL DEFAULT 0,
    currency VARCHAR(10) NULL,
    impressions BIGINT NULL,
    clicks BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_adinsight_ad FOREIGN KEY (ad_id) REFERENCES ads(id),
    UNIQUE KEY uq_ad_insights_ad_date (ad_id, stat_date)
);

CREATE INDEX idx_ad_insights_date ON ad_insights (stat_date);

-- Meta's referral.source_id, but ONLY ever populated when source_type is
-- "ad" (a boosted/organic "post" referral's source_id is a composite
-- {page_id}_{post_id}, not a numeric Meta ad id, and must never be written
-- here — see WhatsAppMessage.adSourceId's Javadoc). This is the join key to
-- ads.external_ad_id.
ALTER TABLE whatsapp_messages ADD COLUMN ad_source_id VARCHAR(100) NULL;

-- Mirrored the same way is_from_ad/ad_headline/ad_source_url already are.
ALTER TABLE whatsapp_conversations ADD COLUMN ad_source_id VARCHAR(100) NULL;

-- Copied from the originating conversation's ctwa_clid at lead-creation
-- time (known immediately, unlike ad_id, which depends on ad-sync timing).
-- Future-proofs sending offline conversion events back to Meta via the
-- Conversions API (CAPI), which matches on this value.
ALTER TABLE leads
    ADD COLUMN ad_id BIGINT NULL,
    ADD COLUMN ctwa_clid VARCHAR(255) NULL,
    ADD CONSTRAINT fk_leads_ad FOREIGN KEY (ad_id) REFERENCES ads(id) ON DELETE SET NULL;
