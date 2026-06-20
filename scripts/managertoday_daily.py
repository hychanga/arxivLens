#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Daily ingest: 經理人雜誌 (Manager Today) Gmail newsletter -> arxivLens.

Reads the daily "每日學管理電子報" emails (sender mteditor@managertoday.com.tw)
over Gmail IMAP, extracts the featured article links (the email body only has
click-tracking redirects), resolves each to its real managertoday URL, scrapes
title/summary/date/author from the public article page, then POSTs each into
arxivLens via POST /api/papers/manual. Server-side de-dup (409) makes re-runs safe.

Standard library only — no pip install needed in CI.

Required env:
  GMAIL_USER            Gmail address (e.g. hychanga@gmail.com)
  GMAIL_APP_PASSWORD    Gmail App Password (16 chars; NOT the account password)
  ARXIVLENS_EMAIL       arxivLens login email
  ARXIVLENS_PASSWORD    arxivLens login password
Optional env:
  ARXIVLENS_API         default https://arxivlens-backend-880984423210.asia-east1.run.app/api
  MT_SOURCE_ID          default 5880001 (the 經理人雜誌 source id)
  MT_DAYS               look-back window in days, default 2
  MT_SENDER             default mteditor@managertoday.com.tw
"""
import email
import imaplib
import json
import os
import re
import sys
from datetime import datetime, timedelta, timezone
from email.header import decode_header
from http.cookiejar import CookieJar
from urllib.parse import parse_qs, unquote, urlparse
import urllib.error
import urllib.request

API = os.environ.get("ARXIVLENS_API",
                     "https://arxivlens-backend-880984423210.asia-east1.run.app/api").rstrip("/")
SOURCE_ID = int(os.environ.get("MT_SOURCE_ID", "5880001"))
DAYS = int(os.environ.get("MT_DAYS", "2"))
SENDER = os.environ.get("MT_SENDER", "mteditor@managertoday.com.tw")
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) arxivLens-mt-ingest"

TRACK_RE = re.compile(r"https://email\.managertoday\.com\.tw/c/[A-Za-z0-9_\-]+")
CONTENT_RE = re.compile(r"managertoday\.com\.tw/(?:articles/view|books/view|eightylife/article/view)/\d+")

# One opener with a cookie jar: the site bounces cookieless requests in a
# redirect loop, so cookies are needed to reach the real article page.
_opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))
_opener.addheaders = [("User-Agent", UA)]


def die(msg, code=1):
    print(f"::error::{msg}")
    sys.exit(code)


# ---------- Gmail (IMAP) ----------

def _decode(s):
    if not s:
        return ""
    out = []
    for part, enc in decode_header(s):
        if isinstance(part, bytes):
            out.append(part.decode(enc or "utf-8", "ignore"))
        else:
            out.append(part)
    return "".join(out)


def _body_text(msg):
    """Return the best text body (plain preferred), decoded with the part charset."""
    candidates = []  # (priority, text)  lower priority wins
    if msg.is_multipart():
        parts = msg.walk()
    else:
        parts = [msg]
    for p in parts:
        ctype = p.get_content_type()
        if ctype not in ("text/plain", "text/html"):
            continue
        payload = p.get_payload(decode=True)
        if not payload:
            continue
        cs = p.get_content_charset() or "utf-8"
        for enc in (cs, "utf-8", "big5", "latin-1"):
            try:
                txt = payload.decode(enc)
                break
            except Exception:
                txt = None
        if txt is None:
            txt = payload.decode("utf-8", "ignore")
        candidates.append((0 if ctype == "text/plain" else 1, txt))
    candidates.sort(key=lambda c: c[0])
    # Concatenate all (plain first) so we never miss a link.
    return "\n".join(t for _, t in candidates)


def fetch_newsletter_bodies():
    user = os.environ.get("GMAIL_USER") or die("GMAIL_USER not set")
    pw = os.environ.get("GMAIL_APP_PASSWORD") or die("GMAIL_APP_PASSWORD not set")
    since = (datetime.now(timezone.utc) - timedelta(days=DAYS)).strftime("%d-%b-%Y")
    bodies = []
    M = imaplib.IMAP4_SSL("imap.gmail.com")
    try:
        M.login(user, pw)
    except imaplib.IMAP4.error as e:
        die(f"Gmail IMAP login failed: {e}. Check GMAIL_APP_PASSWORD (App Password, IMAP enabled).")
    try:
        M.select("INBOX")
        typ, data = M.search(None, f'(FROM "{SENDER}" SINCE {since})')
        ids = data[0].split() if data and data[0] else []
        print(f"Gmail: {len(ids)} message(s) from {SENDER} since {since}")
        for mid in ids:
            typ, md = M.fetch(mid, "(RFC822)")
            if typ != "OK" or not md or not md[0]:
                continue
            msg = email.message_from_bytes(md[0][1])
            subj = _decode(msg.get("Subject"))
            bodies.append((subj, _body_text(msg)))
            print(f"  - {subj[:60]}")
    finally:
        try:
            M.logout()
        except Exception:
            pass
    return bodies


# ---------- managertoday article scraping ----------

def _http_get(url, timeout=25):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with _opener.open(req, timeout=timeout) as r:
        return r.geturl(), r.read().decode("utf-8", "ignore")


def _meta(html, prop):
    m = re.search(r'<meta property="%s" content="([^"]*)"' % re.escape(prop), html)
    return m.group(1) if m else ""


def resolve_and_scrape(track_url):
    """Follow a tracking link to the real article page and scrape metadata.
    Returns a dict or None if it isn't a content URL."""
    try:
        final, html = _http_get(track_url)
    except Exception as e:
        print(f"  ! fetch failed {track_url[:40]}…: {e}")
        return None
    real = unquote(parse_qs(urlparse(final).query).get("sn_redirect_uri", [""])[0]) or final
    real = re.split(r"[?&]utm_", real)[0]
    if not CONTENT_RE.search(real):
        return None
    # If the tracking link landed on the gcfs redirector page (not the article
    # itself), fetch the real URL to get its HTML.
    if "managertoday.com.tw" not in urlparse(final).netloc:
        try:
            _, html = _http_get(real)
        except Exception as e:
            print(f"  ! fetch real failed {real[:40]}…: {e}")
            return None
    title = re.sub(r"\s*\|\s*經理人.*$", "", _meta(html, "og:title")).strip()
    desc = _meta(html, "og:description").strip()
    dp = re.search(r'"datePublished"\s*:\s*"([^"]+)"', html)
    au = re.search(r'"author"\s*:\s*\[?\s*\{[^}]*?"name"\s*:\s*"([^"]+)"', html)
    pub = None
    if dp:
        try:
            pub = datetime.fromisoformat(dp.group(1)).astimezone(timezone.utc).isoformat()
        except Exception:
            pub = None
    if not title:
        return None
    return {"url": real, "title": title, "content": desc or title,
            "author": au.group(1) if au else None, "publishedAt": pub}


# ---------- arxivLens API ----------

def login():
    email_ = os.environ.get("ARXIVLENS_EMAIL") or die("ARXIVLENS_EMAIL not set")
    pw = os.environ.get("ARXIVLENS_PASSWORD") or die("ARXIVLENS_PASSWORD not set")
    body = json.dumps({"email": email_, "password": pw}).encode()
    req = urllib.request.Request(API + "/auth/login", data=body, method="POST",
                                 headers={"Content-Type": "application/json", "User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            tok = json.loads(r.read()).get("token")
            if not tok:
                die("Login succeeded but no token in response")
            return tok
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "ignore")
        die(f"arxivLens login failed ({e.code}): {detail[:200]}")


def post_article(token, art):
    payload = {"sourceId": SOURCE_ID, "title": art["title"], "content": art["content"],
               "url": art["url"]}
    if art.get("author"):
        payload["author"] = art["author"]
    if art.get("publishedAt"):
        payload["publishedAt"] = art["publishedAt"]
    req = urllib.request.Request(API + "/papers/manual", data=json.dumps(payload).encode(),
                                 method="POST",
                                 headers={"Content-Type": "application/json",
                                          "Authorization": "Bearer " + token, "User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read())
        except Exception:
            return e.code, {}


def main():
    bodies = fetch_newsletter_bodies()
    if not bodies:
        print("No newsletters in window — nothing to do.")
        return

    tracks = []
    for _, body in bodies:
        tracks += TRACK_RE.findall(body)
    seen = set()
    tracks = [t for t in tracks if not (t in seen or seen.add(t))]
    print(f"{len(tracks)} unique tracking link(s) to resolve")

    articles, urls_seen = [], set()
    for t in tracks:
        art = resolve_and_scrape(t)
        if art and art["url"] not in urls_seen:
            urls_seen.add(art["url"])
            articles.append(art)
            print(f"  • {art['title'][:55]}")
    print(f"{len(articles)} content article(s) found")
    if not articles:
        return

    token = login()
    ok = dup = err = 0
    for art in articles:
        st, body = post_article(token, art)
        if st == 201:
            ok += 1
            print(f"  +201 id={body.get('id')} {art['title'][:40]}")
        elif st == 409:
            dup += 1
            print(f"  ~409 {body.get('code')} {art['title'][:40]}")
        else:
            err += 1
            print(f"  !{st} {body} {art['title'][:40]}")
    print(f"\ndone: inserted={ok} skipped(dup)={dup} error={err}")
    if err:
        sys.exit(1)


if __name__ == "__main__":
    main()
