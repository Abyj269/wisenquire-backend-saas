# Runbook: client IP and trusted proxies

Security hotfix, module 02 (finding 2026-11-24): the client IP used for the **login rate limits**, **branch IP restrictions**, the **trusted-user OTP skip** and the **audit log** comes from `config/ClientIpResolver`, not from `request.getRemoteAddr()`.

## How the IP is chosen
1. The TCP peer of the connection (who actually connected to Tomcat).
2. If that peer is **not** a trusted proxy, it is the client: `X-Forwarded-For` is ignored.
3. If it **is** a trusted proxy, `X-Forwarded-For` is read from the right, and the first address that is not itself a trusted proxy is the client. (A client can put anything at the *left* of that header; the proxy appends the real address on the *right*.)

## Setting: `app.security.trusted-proxies`
Comma-separated CIDRs (environment variable `APP_SECURITY_TRUSTEDPROXIES`). Default:
```
127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,fc00::/7
```
This fits nginx on the same server (127.0.0.1) and the staging Docker network (172.x).

## Before deploying to wiseNquire_saas_live: check the proxy
1. Which machine connects to Tomcat? If nginx (or Apache) runs **on the same server** and proxies to `127.0.0.1`, the default is right; nothing to set.
2. If the proxy or load balancer runs **elsewhere on a public address**, add exactly that address, e.g. `APP_SECURITY_TRUSTEDPROXIES=127.0.0.0/8,203.0.113.10/32`. Without it, every user appears as the proxy's IP: they would share one login rate limit (30 per minute) and IP-restricted branches would see the proxy.
3. The proxy must **append** the client address: nginx `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` (the staging nginx does this).
4. If Tomcat has a `RemoteIpValve` configured, it already puts the real client in the connection address; the resolver then treats that as the client. Either setup works; do not trust a proxy range the public can connect from.

## Checks after deploying
- Log in once and read the audit or login log: the IP must be your real public IP, not the proxy's and not a value you made up.
- From one machine, send 31 wrong logins within a minute, each with a different fake header:
  `curl -s -o /dev/null -w '%{http_code}\n' -H 'Content-Type: application/json' -H "X-Forwarded-For: 203.0.113.$i" -d '{"email":"probe'$i'@example.invalid","password":"x"}' https://<live-api>/api/v1/auth/login`
  The 31st must be **429**. (Before the fix it stayed 401.)
- Rollback: redeploy the previous WAR. There is no flag: the old behaviour is the vulnerability.

## Login rate limits (module 02 P1, F-13)
Per instance, in memory (`service/RequestRateLimiter`). Restarting an instance clears them.

| Key space | Limit | Store size |
|---|---|---|
| `login-ip` (client IP, see above) | 30 logins / minute | 200,000 keys |
| `login-user` (email) | 10 logins / minute | 100,000 keys |
| `otp` (challenge) | 8 tries / 5 minutes | 100,000 keys |
| `otp-issue` (user) | 1 OTP / minute | 100,000 keys |

- **Before F-13:** once 20,000 keys were held in total, every NEW key got 429, so a flood of logins with random emails locked out every user.
- **Now:** each key space has its own store; entries expire with their window; a full store evicts its least valuable entries. A key is never refused because a store is full (the limit "fails open" for evicted keys instead of blocking real users).
- A refused login is 429 `RATE_LIMITED` with `Retry-After: 60`.
- With 2 or more instances each keeps its own counts (limits multiply by the number of instances) until S12 adds a shared limiter.
