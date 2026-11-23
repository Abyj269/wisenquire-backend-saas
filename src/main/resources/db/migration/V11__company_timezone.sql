-- Company-wide display timezone. Storage is unchanged (every LocalDateTime
-- column keeps writing the server's own wall-clock, which today is
-- Asia/Dubai) — this only controls what zone timestamps are converted to
-- when shown in the UI (see the frontend's companyDate pipe) and what "now"
-- means when evaluating a branch's business hours/holidays.
ALTER TABLE companies
    ADD COLUMN timezone varchar(64) NOT NULL DEFAULT 'Asia/Dubai';
