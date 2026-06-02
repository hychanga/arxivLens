---
name: paas-cron-jobs
description: >-
  Make recurring/scheduled background jobs actually run on a free or
  sleep-prone PaaS (Render free, Fly scale-to-zero, etc.), and send
  completion-notification emails reliably. Covers a token-protected trigger
  endpoint, an external scheduler (GitHub Actions / cron-job.org), disabling the
  unreliable in-process scheduler, and best-effort email on hosts that block
  SMTP. TRIGGER when the user wants a periodic job (sync/cleanup/report) to fire
  on schedule, says their cron / @Scheduled job "isn't running" or "only runs
  when I use the app", or wants an email/notification when a scheduled job finishes.
---

# Reliable scheduled jobs + notifications on free PaaS

From shipping a 6-hourly sync + email on Render's free tier. Stack-agnostic.

## The core problem
Free/idle-scaled PaaS **spins the service down after ~15 min idle**. While it's
asleep, **in-process schedulers do not fire** (`@Scheduled`, node-cron, APScheduler,
…). So a job set for 00/06/12/18:00 almost never runs — yet a manual test from
the UI works, because using the app keeps the service awake. Symptom: "the
scheduled job / email only happens when I'm actively using it."

## The fix: external trigger → token-protected endpoint
Don't rely on the in-process clock. Have a **free external scheduler POST to an
endpoint** every N hours. The request both **wakes the service** and **runs the
work**.

### 1. Token-protected trigger endpoint
- Auth with a **shared secret** (not a user JWT — the caller has no session).
  Accept it via `?token=` or an `X-Cron-Token` header; **constant-time compare**.
- `permitAll` at the security-filter layer; the handler enforces the token.
- Return **503** if the token isn't configured, **401** if it's wrong.
- **Run the work on a background thread and return an ack immediately.** A long
  job outlives any proxy/browser request timeout; the side effect (e.g. a
  summary email) is the real result. Reuse one code path for both the in-process
  scheduler and the endpoint.
```java
@PostMapping("/api/cron/run-sync")
Map<String,Object> run(@RequestParam(required=false) String token,
                       @RequestHeader(name="X-Cron-Token", required=false) String hdr) {
  authorize(token != null ? token : hdr);          // 503 if unset, 401 if bad
  executor.submit(scheduler::runSyncAndNotify);     // background; daemon single-thread
  return Map.of("status","started");
}
```

### 2. External scheduler — GitHub Actions (free, no extra signup)
Commit `.github/workflows/<job>-cron.yml`. Store the secret as a repo secret;
guard so it skips (not fails) until set; add retries for the cold-start wake.
```yaml
on:
  schedule: [{ cron: "0 */6 * * *" }]   # 00/06/12/18:00 UTC; GitHub may delay a few min
  workflow_dispatch: {}                 # manual "Run workflow" for testing
jobs:
  trigger:
    runs-on: ubuntu-latest
    steps:
      - env:
          CRON_TOKEN: ${{ secrets.CRON_TOKEN }}
          BACKEND_URL: ${{ vars.BACKEND_URL || 'https://your-service.onrender.com' }}
        run: |
          [ -z "$CRON_TOKEN" ] && { echo "::warning::CRON_TOKEN not set — skipping"; exit 0; }
          curl -fsS --max-time 120 --retry 3 --retry-all-errors --retry-delay 30 \
            -X POST -H "X-Cron-Token: $CRON_TOKEN" "$BACKEND_URL/api/cron/run-sync"
```
Caveats: GitHub schedules can lag a few minutes and get **disabled after 60 days
of repo inactivity** (re-enable in the Actions tab). `cron-job.org` is an
alternative (set request timeout ≥30s for the cold start).

### 3. Turn OFF the in-process scheduler
Once external cron drives it, disable the in-process one (e.g.
`SCHEDULER_ENABLED=false`) so it doesn't double-fire on the rare occasion the
service is awake at a tick. The trigger endpoint runs regardless of that flag.

## Completion-notification email (and PaaS email reality)
- **PaaS often blocks outbound SMTP** (Render free blocks 25/465/587). Use an
  **HTTP email API (Resend, etc.)** instead; keep SMTP as a local-dev fallback.
- **Best-effort:** log and swallow provider errors so a mail hiccup never turns a
  job into a 5xx. Build the message from the job's result (inserted/skipped/error).
- **Config-gate the recipient** (`SYNC_NOTIFY_EMAIL`); blank = no email.
- **Resend dev sandbox** (`onboarding@resend.dev`) only delivers to the address
  you registered with Resend — verify your own domain to send elsewhere.
- Add a **manual test trigger** (admin button or `/cron` call) to verify the
  email path without waiting for the schedule. If the manual test mail arrives
  but the scheduled one doesn't, the job isn't firing → it's the sleep problem
  above, not email.

## Verify
1. Set the secret on **both** sides (PaaS env + scheduler secret) — same value.
2. Trigger manually (`gh workflow run …`, the Actions "Run workflow" button, or a
   direct `curl` with the token) → expect the ack, then the side effect
   (email / data change) within a couple of minutes.
3. Confirm in logs the job ran end to end. Document the whole setup (env vars +
   endpoint + scheduler) in DEPLOY/README — it's non-obvious to future-you.

## Gotchas
- Don't put the long job on the request thread — background it.
- `404/405` right after deploy = frontend/scheduler is ahead of the backend
  (deploy skew); wait for the backend to finish.
- Keep the trigger idempotent/guarded (see the resilient-sync skill) so an
  accidental double-fire is harmless.
