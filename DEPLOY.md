# Deploying arxivLens to free hosting (Vercel + Render + TiDB Cloud)

This guide walks you from a local arxivLens to a publicly-reachable one running entirely on free tiers. End state:

```
                                 ┌─────────────────────┐
       https://arxivlens         │   Vercel (frontend) │
       .vercel.app    ───────►   │   Next.js 16        │
                                 └────────┬────────────┘
                                          │ NEXT_PUBLIC_API_URL
                                          ▼
                              https://arxivlens-backend
                                  .onrender.com
                                          │
                                 ┌────────┴────────────┐
                                 │   Render (backend)  │
                                 │   Spring Boot 4     │
                                 └────────┬────────────┘
                                          │ JDBC
                                          ▼
                              ┌──────────────────────┐
                              │  TiDB Cloud Server-  │
                              │  less (MySQL-compat) │
                              └──────────────────────┘
```

Free-tier limits worth knowing up front:

| Service              | Free tier                                      | Caveats                                                  |
| -------------------- | ---------------------------------------------- | -------------------------------------------------------- |
| **Vercel** Hobby     | 100 GB bandwidth/mo, builds unlimited          | Personal use only                                        |
| **Render** Free      | 512 MB RAM, 0.1 CPU, **sleeps after 15 min**   | First request after sleep takes ~30 s to wake            |
| **TiDB Cloud** Serverless | 25 GB storage, 250 M Request Units/mo    | Region pick is permanent; mandatory TLS                  |
| **Google AI Studio** Gemini free | 1500 reqs/day                       | Quota resets 00:00 PT                                    |

## 0. Prerequisites

- A GitHub account and a public/private repo containing this project.
- A Google account (for Gemini API key + sign-up to TiDB Cloud uses Google SSO).
- The repo's `frontend/`, `backend/`, `render.yaml`, this file at the root.

If you don't yet have a git repo:

```powershell
cd c:\Greg\Project\arxivLens
git init -b main
git add .
git commit -m "Initial commit: arxivLens"
git remote add origin git@github.com:<your-user>/arxivlens.git
git push -u origin main
```

## 1. Provision the database (TiDB Cloud Serverless)

1. Sign up at <https://tidbcloud.com> → Sign in with Google.
2. Create a **Serverless** cluster (the only free option).
   - Region: any AWS region works. The DB and backend can be in different regions — TiDB region choice is permanent on the free tier, so pick one that's likely to stay close to wherever you might host the backend later. `Singapore (ap-southeast-1)` and `Oregon (us-west-2)` are both fine; this guide later puts the backend on Render Oregon (Render's Singapore IPs are blocked by Google's Gemini geo-IP filter — see step 2). Cross-region adds ~150 ms per query but is acceptable for hobby use.
   - Cluster name: `arxivlens` (anything works).
3. Once the cluster is `ACTIVE`, click **Connect**:
   - Connection type: **Public**.
   - Connect with: **JDBC**.
   - Click **Generate Password**, save the password somewhere safe.
4. Note the connection details:
   - **Host:** `gateway01.<region>.prod.aws.tidbcloud.com`
   - **Port:** `4000`
   - **User:** something like `2xK7…root`
   - **Password:** what you generated
5. Create the application database. **Where:** in TiDB Cloud's left sidebar click **SQL Editor** (some accounts call it **Chat2Query**). Pick your cluster from the dropdown at the top of the editor, paste the statement below, and hit **Run** (or `Ctrl+Enter`):
   ```sql
   CREATE DATABASE arxivlens CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   ```
   Expected output: `Query OK, 0 rows affected`. If your account also has a default `test` database you can use that instead, but `arxivlens` is cleaner.

6. Build the JDBC URL the backend will use. **Where:** just in a text editor / sticky note — you'll paste it into Render in step 2.3, this isn't a UI action in TiDB. Take the template below and replace `<HOST>` with the host from step 1.4:
   ```
   jdbc:mysql://<HOST>:4000/arxivlens?useSSL=true&requireSSL=true&enabledTLSProtocols=TLSv1.2,TLSv1.3&serverTimezone=UTC&characterEncoding=utf8
   ```
   Concrete example after substitution:
   ```
   jdbc:mysql://gateway01.ap-southeast-1.prod.aws.tidbcloud.com:4000/arxivlens?useSSL=true&requireSSL=true&enabledTLSProtocols=TLSv1.2,TLSv1.3&serverTimezone=UTC&characterEncoding=utf8
   ```
   The `username` and `password` are **not** in the URL — Spring sends them as separate `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` env vars (also set in Render later).

## 2. Deploy the backend (Render)

1. Sign up at <https://render.com> → connect your GitHub.
2. Dashboard → **New +** → **Blueprint** → pick the repo. Render reads `render.yaml`.
3. After the blueprint is detected, Render shows a list of placeholders (the `sync: false` envs). Fill them in:

   | Key                          | Value                                                       |
   | ---------------------------- | ----------------------------------------------------------- |
   | `SPRING_DATASOURCE_URL`      | the JDBC URL from step 1.6                                  |
   | `SPRING_DATASOURCE_USERNAME` | TiDB user                                                   |
   | `SPRING_DATASOURCE_PASSWORD` | TiDB password                                               |
   | `JWT_SECRET`                 | 32+ random chars: `openssl rand -hex 32`                    |
   | `GEMINI_API_KEY`             | from <https://aistudio.google.com/apikey> (or leave blank)  |
   | `APP_CORS_ALLOWED_ORIGINS`   | leave blank for now — fill after Vercel deploys (step 3.4)  |

4. Click **Apply**. First build takes ~5–8 min (Maven download + Docker layer build).
5. When the service shows `Live`, note its URL. Render normally gives you the bare service name (`https://arxivlens-backend.onrender.com`); if your name was already taken Render appends a hash (e.g. `https://arxivlens-backend-abc1.onrender.com`). Use whichever Render shows.
6. Sanity check: `curl https://arxivlens-backend.onrender.com/actuator/health` → `{"status":"UP"}`.

> The free tier sleeps after 15 min idle. The first request after sleep returns in ~30 s. The startup sync runner will refresh real arXiv / HBR data each time the service wakes.

## 3. Deploy the frontend (Vercel)

1. Sign up at <https://vercel.com> → connect GitHub → import the same repo.
2. **Root Directory**: `frontend` (this is critical — the project is a monorepo).
3. Framework: should auto-detect as **Next.js**.
4. **Environment Variables** → Add:

   | Key                      | Value                                                |
   | ------------------------ | ---------------------------------------------------- |
   | `NEXT_PUBLIC_API_URL`    | `https://arxivlens-backend.onrender.com/api` (use the URL from step 2.5) |

   Apply the variable to **Production**, **Preview**, and **Development**. `NEXT_PUBLIC_*` vars are baked in at build time, so any later change to this URL requires a Vercel redeploy.
5. **Deploy**. First build ~2 min.
6. Once live, copy the Vercel URL: `https://arxivlens.vercel.app` (and the auto-generated preview pattern, e.g. `https://arxivlens-*.vercel.app`).
7. Go back to **Render → arxivlens-backend → Environment** and set `APP_CORS_ALLOWED_ORIGINS`. Simplest value is just the production URL:
   ```
   APP_CORS_ALLOWED_ORIGINS=https://arxivlens.vercel.app
   ```
   If you also want PR preview URLs to work, list them comma-separated. Vercel's preview pattern is:
   ```
   https://arxivlens-git-<branch>-<vercel-team-slug>.vercel.app
   ```
   To find your exact team slug: Vercel dashboard → arxivlens project → Deployments → copy the host name from any preview deployment. Free Hobby accounts default to `<username>-projects`, e.g.:
   ```
   APP_CORS_ALLOWED_ORIGINS=https://arxivlens.vercel.app,https://arxivlens-git-main-greg-projects.vercel.app
   ```
   Comma-separated, no trailing slash, no whitespace. Save → Render redeploys (~1 min).
   *(For a single wildcard that covers every preview URL, switch SecurityConfig from `setAllowedOrigins` to `setAllowedOriginPatterns`.)*

### Optional — enable Sign in with Apple

Apple sign-in stays a mock demo user until you wire a real **Services ID**. It
needs a paid **Apple Developer Program** membership ($99/yr).

1. **developer.apple.com → Certificates, IDs & Profiles → Identifiers**:
   - Create an **App ID** with the *Sign in with Apple* capability enabled.
   - Create a **Services ID** (e.g. `com.arxivlens.web`) — this is your
     `client_id` / token `aud`. Enable *Sign in with Apple* on it, link it to
     the App ID, and under **Website URLs** add:
     - **Domain:** `arxivlens.vercel.app`
     - **Return URL:** `https://arxivlens.vercel.app/login`
   - Apple requires domain verification: download the association file it gives
     you and serve it at `https://arxivlens.vercel.app/.well-known/apple-developer-domain-association.txt`
     (drop it in `frontend/public/.well-known/`), then click **Verify**.
2. **Render → arxivlens-backend → Environment:**
   ```
   APPLE_CLIENT_ID=com.arxivlens.web
   ```
   (Comma-separate if you also support a native app bundle ID.) Save → redeploy.
3. **Vercel → arxivlens → Environment Variables** (Production + Preview + Development):
   ```
   NEXT_PUBLIC_APPLE_CLIENT_ID=com.arxivlens.web
   NEXT_PUBLIC_APPLE_REDIRECT_URI=https://arxivlens.vercel.app/login
   ```
   These are baked in at build time, so trigger a Vercel redeploy after setting them.

The login screen swaps the mock Apple button for the real popup flow once both
`NEXT_PUBLIC_APPLE_*` vars are present; the backend verifies the returned token
against Apple's JWKS and matches/creates the user. The `redirectURI` must be
HTTPS and exactly match a registered Return URL even in popup mode.

## 4. First-time bootstrap

1. Open `https://arxivlens.vercel.app`.
2. Login with the demo account: `demo@arxivlens.local` / `demo123` (auto-seeded by `DataSeeder`).
3. Wait ~10 s for the first arXiv sync to finish (cold start). Refresh the Latest panel — real papers appear.
4. As the admin (`admin@arxivlens.local` / `admin123`), you can:
   - **Change passwords** — currently the demo passwords are static. The fastest path is to register a fresh account via the Login → Register tab and discard the demo accounts.
   - Hit **Sync now** in the Admin panel if the auto-sync hasn't fired yet.

## 5. Automated 6-hourly sync + email notifications

The backend refreshes arXiv every 6 hours and emails a summary when it finishes. Because **Render Free spins the service down after ~15 min idle**, the in-process `@Scheduled` job can't reliably fire at the cron times — so the schedule is driven by a free **external** trigger instead.

**How the pieces fit:**

- `POST /api/cron/arxiv-sync` — token-protected endpoint (no login needed) that runs the sync + sends the summary email. The request also wakes the sleeping service.
- A scheduled **GitHub Actions** workflow (`.github/workflows/sync-cron.yml`, already in the repo) POSTs to it every 6h.
- `EmailService` (Resend) sends the summary to `SYNC_NOTIFY_EMAIL`.

**Setup:**

1. **Backend → Render → Environment:**

   | Key                 | Value                                                                                   |
   | ------------------- | --------------------------------------------------------------------------------------- |
   | `CRON_TOKEN`        | a strong shared secret — `openssl rand -hex 32`                                          |
   | `SYNC_NOTIFY_EMAIL` | where to send the summary (blank disables the email)                                     |
   | `SCHEDULER_ENABLED` | `false` — the external cron drives it now; avoids a duplicate run when the service is awake at a tick |

   Email delivery also needs `RESEND_API_KEY` (see §2). With Resend's dev sandbox the recipient must be the address you registered with Resend.

2. **GitHub → repo → Settings → Secrets and variables → Actions:**
   - Add a repository **secret** `CRON_TOKEN` with the *same* value as the backend.
   - (Optional) a repository **variable** `BACKEND_URL` if your backend isn't at the default `https://arxivlens-backend.onrender.com`.

   The workflow then runs at `00/06/12/18:00 UTC` (GitHub may delay a few minutes). Until `CRON_TOKEN` is set it skips with a warning instead of failing.

**Trigger / test it manually:**

- GitHub → **Actions** → *Scheduled arXiv sync* → **Run workflow**, or `gh workflow run sync-cron.yml`, or
- direct: `curl -X POST "https://arxivlens-backend.onrender.com/api/cron/arxiv-sync?token=<CRON_TOKEN>"` → `{"status":"started"}`, then a summary email arrives in ~1–2 min.
- To test only the **email** path (no sync), use the **Test sync email** button in Admin → Data Sources.

**Prefer a different scheduler?** Any cron service works — e.g. cron-job.org: POST that URL every 6h with timeout ≥30 s (for the cold-start wake). Alternatively set `SCHEDULER_ENABLED=true` and keep the service awake with an uptime pinger, but that's less reliable and burns the free instance-hours.

## 6. Updating

Push to `main`. Vercel and Render both auto-redeploy:

```powershell
git push origin main
```

Vercel typically finishes in ~1 min, Render in ~5 min (Docker rebuild).

## 7. Common gotchas

| Symptom                                                                  | Cause                                                                                | Fix                                                                                       |
| ------------------------------------------------------------------------ | ------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------- |
| Frontend loads but API calls all 401 / network error                     | CORS — `APP_CORS_ALLOWED_ORIGINS` doesn't include the exact Vercel URL               | Add the URL (no trailing slash) and restart Render service                                |
| Backend health check fails / `out of memory` in Render logs              | 512 MB is tight. Some startup spike pushes over.                                     | Lower `MaxRAMPercentage` to 60, switch to `-XX:+UseSerialGC` (already set in render.yaml) |
| Login works but Latest is empty for a long time                          | First wake-up sync is still running, or Render slept                                 | Refresh after 15 s. If still empty, check Render logs for sync errors                     |
| 6-hourly sync / notification email never runs                            | Render Free sleeps the service, so the in-process `@Scheduled` job doesn't fire      | Drive it externally — see §5 (set `CRON_TOKEN` + the GitHub Actions secret; `SCHEDULER_ENABLED=false`) |
| `Gemini 403: API_KEY_INVALID`                                            | Wrong / unenabled key                                                                | Regenerate key in AI Studio; key must come from `aistudio.google.com/apikey` (not Cloud Console) |
| `Gemini 400: User location is not supported`                             | Render egress IP geo-blocked by Google (Singapore, some EU regions)                  | Move the Render service to `oregon` (already the default in `render.yaml`). Region change requires creating a new service and re-entering all `sync: false` env vars |
| `Communications link failure` to TiDB                                    | Wrong region or missing TLS args                                                     | URL must include `useSSL=true&enabledTLSProtocols=TLSv1.2,TLSv1.3`                        |
| Render build fails: `Cannot find symbol DataSeeder constructor argument` | Old artifact compiled before the seeder change                                       | Force rebuild from Render → service → Manual Deploy → Clear build cache & deploy          |

## 8. Things this setup intentionally does NOT do

- **No Flyway / Liquibase** — schema is managed by Hibernate `ddl-auto=update`. Fine for hobby, not for production with real users.
- **No backups** — TiDB Cloud Serverless free tier has a basic point-in-time recovery window, but no scheduled exports. If your data matters, export manually.
- **No CDN / image optimization** — Vercel handles it for the Next.js side; backend serves PDFs directly which is slow over Render free.
- **No custom domain** — both Vercel and Render let you bring a domain (free), but DNS is on you.

## 9. Switching off / tearing down

| Platform     | Where                                                                |
| ------------ | -------------------------------------------------------------------- |
| Vercel       | Dashboard → project → Settings → Advanced → Delete Project          |
| Render       | Dashboard → service → Settings → Delete Service                     |
| TiDB Cloud   | Dashboard → cluster → "…" → Pause Cluster (free tier auto-pauses too) |

Pausing TiDB drops the data; export `mysqldump` if you want to keep it.
