"""Log in like the browser: password -> OTP challenge -> OTP read from Mailpit -> JWT.

Staging and CI only (it reads the login OTP from a local Mailpit). Prints the JWT on stdout.

    python otp_login.py --base http://127.0.0.1:8180/api/v1 --mail http://127.0.0.1:8025 \
        --email agent1.c1b1@t01.staging.test --password '...'
"""
import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.request


def call(method, url, body=None, origin=None, token=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method)
    if body is not None:
        req.add_header("Content-Type", "application/json")
    if origin:
        req.add_header("Origin", origin)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, parse(r.read())
    except urllib.error.HTTPError as e:
        return e.code, parse(e.read())


def parse(raw):
    """JSON body, or {"raw": text} for empty / non-JSON bodies (e.g. Mailpit's "ok")."""
    try:
        return json.loads(raw) if raw else {}
    except ValueError:
        return {"raw": raw.decode(errors="replace")}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base", required=True, help="API base incl. /api/v1")
    p.add_argument("--mail", required=True, help="Mailpit base URL")
    p.add_argument("--email", required=True)
    p.add_argument("--password", required=True)
    p.add_argument("--origin", default=None, help="Origin header (same-origin check through nginx)")
    a = p.parse_args()

    call("DELETE", a.mail + "/api/v1/messages")
    status, resp = call("POST", a.base + "/auth/login", {"email": a.email, "password": a.password}, a.origin)
    if status != 200:
        sys.exit(f"login failed: HTTP {status} {resp}")
    token = resp.get("token")
    if not token:
        challenge = resp.get("challengeId")
        if not challenge:
            sys.exit(f"login returned neither token nor challenge: {resp}")
        otp = None
        for _ in range(30):
            _, listing = call("GET", a.mail + "/api/v1/messages")
            for m in listing.get("messages") or []:
                to = [t.get("Address", "").lower() for t in m.get("To") or []]
                if a.email.lower() in to:
                    _, msg = call("GET", a.mail + "/api/v1/message/" + m["ID"])
                    found = re.search(r"\b(\d{4,8})\b", msg.get("Text", ""))
                    if found:
                        otp = found.group(1)
                        break
            if otp:
                break
            time.sleep(1)
        if not otp:
            sys.exit("no OTP email for " + a.email + " arrived in Mailpit")
        status, resp = call("POST", a.base + "/auth/verify-otp",
                            {"email": a.email, "otp": otp, "challengeId": challenge}, a.origin)
        token = resp.get("token")
        if status != 200 or not token:
            sys.exit(f"OTP verification failed: HTTP {status} {resp}")
    print(token)


if __name__ == "__main__":
    main()
