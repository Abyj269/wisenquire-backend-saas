-- Module 16 P1 (S3-a / P-8): heartbeat of the chunked broadcast sender (flag broadcast.chunked_tx).
-- Additive only. NULL for broadcasts sent by the original single-transaction path.
ALTER TABLE broadcasts ADD COLUMN last_heartbeat_at DATETIME(6) NULL;
CREATE INDEX idx_broadcasts_status_heartbeat ON broadcasts (status, last_heartbeat_at);
