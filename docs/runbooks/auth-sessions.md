# Runbook: sign-in sessions and password reset (module 02, Phase 2)

## Token subject: `auth.subject_user_id` (default OFF, per org)
- **ON:** new access tokens carry the user id as their subject (plus an `email` claim). **OFF:** the email, as before.
- **Both forms are always accepted:** a token issued before the switch keeps working until it expires (24 h, or 15 minutes with refresh tokens). Turning the flag off again is safe for the same reason.
- Nothing else changes: the signed-in identity is still the user's email inside the app (permissions, the per-user chat queue, logs).

## Refresh tokens: `auth.refresh_tokens` (default OFF, per org)
- **Keep it OFF until the frontend refresh step ships.** Without the frontend's silent refresh, users would be signed out every 15 minutes.
- **ON:** login answers a **15-minute** access token (`app.security.jwt.access-expiration-ms`) plus a **7-day** refresh token (`app.security.jwt.refresh-expiration-ms`). **OFF:** a 24-hour access token and no refresh token, as before.
- `POST /auth/refresh {refreshToken}` returns a new access token and a **new** refresh token; the old one stops working. The table is `refresh_tokens` (V60) and stores only a SHA-256 hash of each token.
- **A login ends** (401 `SESSION_REVOKED`, the user signs in again) when:
  - an already-replaced token is presented more than 20 seconds after it was replaced (a stolen copy), which ends the whole login, the newest token included;
  - the token expired, or the user logged out (`POST /auth/logout {refreshToken}`);
  - the user's `session_version` changed (role edit, password reset, deactivation), or the user or org is inactive;
  - the org turned the flag off.
- Two tabs refreshing with the same token within 20 seconds both succeed.
- **Rollback:** turn the flag off for the org. Its users are on 24-hour logins again at their next sign-in, and existing refresh tokens are refused.

```sql
-- active logins of one user
SELECT family_id, MIN(created_at) AS signed_in, MAX(created_at) AS last_refresh FROM refresh_tokens
 WHERE user_id = <id> AND revoked_at IS NULL GROUP BY family_id;
-- sign one user out everywhere (or raise users.session_version by 1)
UPDATE refresh_tokens SET revoked_at = NOW(6) WHERE user_id = <id> AND revoked_at IS NULL;
```

## Password reset (no flag)
- `POST /public/password-reset {email}` always answers **202**, whether or not the address has an account. An active account gets an email with `<app.public-url>/reset-password?token=…`.
  - The link works once, for 30 minutes (`app.security.password-reset.expiry-minutes`); a newer request replaces an older link.
  - The email is queued in `email_outbox` in the same transaction as the token and sent by the `email-outbox` job (see below).
  - Rate limits: 10 requests per 15 minutes per IP, 3 per address.
- `POST /public/password-reset/confirm {token, newPassword}`: 204 on success; the password must be 8–128 characters; every session of the user ends. 400 `VALIDATION_FAILED` for a bad, used or expired link.
- **Set `APP_PUBLIC_URL`** on every deployment to the address users open the app at; the default is `http://localhost:4200`.
- The frontend page `/reset-password` arrives in a later Phase 2 step; until then a link opens the app without that page.

## Email outbox and templates (Step 6.2, no flag)
- **Templates** (`src/main/resources/templates/email/`, HTML + text each): OTP, VERIFY_EMAIL, INVITE, PASSWORD_RESET, TRIAL_EXPIRING, PAYMENT_FAILED, INVOICE_ISSUED. Today only OTP and PASSWORD_RESET are sent; the others are ready for later modules. Product name: `APP_MAIL_PRODUCT_NAME` (default WiseNquire).
- **Login OTP:** still sent synchronously (the user waits for it), after the login transaction committed, never through the outbox. A failed send answers the login with an error, as before.
- **Everything else goes through `email_outbox`** (V62): queued in the caller's transaction (nothing is sent if it rolls back), sent by the `email-outbox` job with no transaction open, in batches of 20 claimed with `FOR UPDATE SKIP LOCKED`.
  - Status: `PENDING` → `SENDING` → `SENT`; a failed send is retried after 1 min, 5 min, 15 min, 1 h, 6 h, then `FAILED` (6 attempts, `last_error` kept). A row left `SENDING` for 10 minutes (node stopped) is retried.
  - `SUPPRESSED`: the address is in `email_suppressions` (V63); nothing is sent.
  - Log lines mask the address (`j***@example.com`).
- **Bounce webhook** `POST /email/webhook/bounce` (public, signed): body `{"email": "...", "type": "hard_bounce" | "complaint" | "soft_bounce"}`, header `X-Signature: sha256=<hex HMAC-SHA256 of the raw body with EMAIL_WEBHOOK_SECRET>`.
  - No secret set: the webhook is off (401). Missing or wrong signature: 401. Unknown type or no address: 400. Success: 204.
  - Hard bounce and complaint suppress the address (case-insensitive); a soft bounce is ignored (the outbox retries).
- **Sender domain:** SPF, DKIM, DMARC in `EMAIL_DNS.md` (domain not chosen yet; placeholder `mail.example.com`).

```sql
-- what is waiting or failing
SELECT status, COUNT(*) FROM email_outbox GROUP BY status;
SELECT id, template, attempts, next_attempt_at, last_error FROM email_outbox WHERE status IN ('PENDING','FAILED') ORDER BY id DESC LIMIT 50;
-- send a FAILED email again
UPDATE email_outbox SET status = 'PENDING', attempts = 0, next_attempt_at = NOW(6) WHERE id = <id>;
-- allow email to a suppressed address again (after the owner fixed the mailbox)
DELETE FROM email_suppressions WHERE address = LOWER('<address>');
```
- **Rollback:** the outbox only carries the password reset today. Stopping the job (`email.outbox.check-ms` very high) holds emails as `PENDING`; they go out when it runs again.
