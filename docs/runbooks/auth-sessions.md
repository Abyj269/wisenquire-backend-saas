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
  - The email is sent on the `mail-` thread after the request answered (no transaction open).
  - Rate limits: 10 requests per 15 minutes per IP, 3 per address.
- `POST /public/password-reset/confirm {token, newPassword}`: 204 on success; the password must be 8–128 characters; every session of the user ends. 400 `VALIDATION_FAILED` for a bad, used or expired link.
- **Set `APP_PUBLIC_URL`** on every deployment to the address users open the app at; the default is `http://localhost:4200`.
- The frontend page `/reset-password` arrives in a later Phase 2 step; until then a link opens the app without that page.
- Mail goes through the existing SMTP settings until the email outbox (Step 6.2). A failed send is logged as "Password reset email delivery failed."
