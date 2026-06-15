# Deploying arxivLens to free hosting (Vercel + Google Cloud Run + TiDB Cloud)

This guide walks you from a local arxivLens to a publicly-reachable one running entirely on free / always-free tiers. End state:

```
                                 ┌─────────────────────┐
       https://arxivlens         │   Vercel (frontend) │
       .vercel.app    ───────►   │   Next.js 16        │
                                 └────────┬────────────┘
                                          │ NEXT_PUBLIC_API_URL
                                          ▼
                          https://arxivlens-backend-…
                              .asia-east1.run.app
                                          │
                                 ┌────────┴────────────┐
                                 │ Google Cloud Run    │
                                 │ (backend)           │
                                 │ Spring Boot 4       │
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
| **Cloud Run**        | 2 M requests/mo, 180k vCPU-s, 360k GiB-s, scales to zero | Cold start (~a few s) after idle when `min-instances=0`  |
| **Cloud Build**      | 2,500 build-min/mo on the default `e2-standard-2` machine | Don't set a custom `machineType` — billed from minute one |
| **TiDB Cloud** Serverless | 25 GB storage, 250 M Request Units/mo    | Region pick is permanent; mandatory TLS                  |
| **Google AI Studio** Gemini free | 1500 reqs/day                       | Quota resets 00:00 PT                                    |

## 0. Prerequisites

- A GitHub account and a public/private repo containing this project.
- A Google account (for Gemini API key + sign-up to TiDB Cloud uses Google SSO).
- A **Google Cloud project** with billing enabled (Cloud Run / Cloud Build / Secret Manager require an active billing account even though usage stays within the free tier).
- The [`gcloud` CLI](https://cloud.google.com/sdk/docs/install) installed and authenticated (`gcloud auth login`, then `gcloud config set project <YOUR_PROJECT_ID>`).
- The repo's `frontend/`, `backend/`, `cloudbuild.yaml`, this file at the root.

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
   - Region: any AWS region works. The DB and backend can be in different regions — TiDB region choice is permanent on the free tier, so pick one close to where you'll host the backend. This guide puts the backend on Cloud Run `asia-east1` (Taiwan), so a nearby TiDB region such as `Singapore (ap-southeast-1)` or `Tokyo (ap-northeast-1)` keeps latency low. Cross-region adds ~150 ms per query but is acceptable for hobby use. (Cloud Run's `asia-east1` egress is not geo-blocked by Gemini, so you don't need a US region.)
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

6. Build the JDBC URL the backend will use. **Where:** just in a text editor / sticky note — you'll store it in Secret Manager in step 2.3, this isn't a UI action in TiDB. Take the template below and replace `<HOST>` with the host from step 1.4:
   ```
   jdbc:mysql://<HOST>:4000/arxivlens?useSSL=true&requireSSL=true&enabledTLSProtocols=TLSv1.2,TLSv1.3&serverTimezone=UTC&characterEncoding=utf8
   ```
   Concrete example after substitution:
   ```
   jdbc:mysql://gateway01.ap-southeast-1.prod.aws.tidbcloud.com:4000/arxivlens?useSSL=true&requireSSL=true&enabledTLSProtocols=TLSv1.2,TLSv1.3&serverTimezone=UTC&characterEncoding=utf8
   ```
   The `username` and `password` are **not** in the URL — Spring sends them as separate `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` env vars (also stored as secrets below).

## 2. Deploy the backend (Google Cloud Run)

The backend is a container deployed to Cloud Run in region `asia-east1`. Secrets live in **Secret Manager** and are injected as env vars; non-secret config is set as plain env vars. After the first manual deploy, **Cloud Build** rebuilds and ships a new image on every push to `main` — but it only passes `--image`, so it *preserves* the env/secret config you set here (you change runtime config out-of-band, never in CI).

> The example commands use the values from this project (`asia-east1`, secret names prefixed `ARXIVLENS_`). Swap in your own project id; the region and names are arbitrary.

### 2.1 Enable the APIs (one time)

```bash
gcloud services enable run.googleapis.com cloudbuild.googleapis.com \
  artifactregistry.googleapis.com secretmanager.googleapis.com
```

### 2.2 Create the secrets in Secret Manager

Each value is stored once; Cloud Run reads them at startup. Replace the placeholder values:

```bash
printf '%s' "<JDBC URL from step 1.6>"  | gcloud secrets create ARXIVLENS_TIDB_URL       --data-file=-
printf '%s' "<TiDB user>"               | gcloud secrets create ARXIVLENS_TIDB_USERNAME  --data-file=-
printf '%s' "<TiDB password>"           | gcloud secrets create ARXIVLENS_TIDB_PASSWORD  --data-file=-
printf '%s' "$(openssl rand -hex 32)"   | gcloud secrets create ARXIVLENS_JWT_SECRET     --data-file=-
printf '%s' "<Gemini API key>"          | gcloud secrets create ARXIVLENS_GEMINI_API_KEY --data-file=-
printf '%s' "<Resend API key>"          | gcloud secrets create ARXIVLENS_RESEND_API_KEY --data-file=-
printf '%s' "$(openssl rand -hex 32)"   | gcloud secrets create ARXIVLENS_CRON_TOKEN     --data-file=-
printf '%s' "<Google OAuth client id>"  | gcloud secrets create ARXIVLENS_GOOGLE_CLIENT_ID --data-file=-
```

Grant the Cloud Run runtime service account (the project's default compute SA, `<PROJECT_NUMBER>-compute@developer.gserviceaccount.com`) permission to read them:

```bash
PROJECT_NUMBER=$(gcloud projects describe "$(gcloud config get-value project)" --format='value(projectNumber)')
for s in TIDB_URL TIDB_USERNAME TIDB_PASSWORD JWT_SECRET GEMINI_API_KEY RESEND_API_KEY CRON_TOKEN GOOGLE_CLIENT_ID; do
  gcloud secrets add-iam-policy-binding "ARXIVLENS_$s" \
    --member="serviceAccount:${PROJECT_NUMBER}-compute@developer.gserviceaccount.com" \
    --role="roles/secretmanager.secretAccessor"
done
```

### 2.3 First deploy (builds from source + sets all config)

This single command builds the image from `backend/`, deploys it, and pins every env var + secret binding. `--no-cpu-throttling` is **required**: the cron endpoint and `StartupSyncRunner` do sync work on a background thread *after* the HTTP response returns, which default CPU throttling would freeze.

```bash
gcloud run deploy arxivlens-backend \
  --source=backend \
  --region=asia-east1 \
  --allow-unauthenticated \
  --no-cpu-throttling \
  --min-instances=0 \
  --memory=512Mi \
  --set-env-vars=SPRING_PROFILES_ACTIVE=prod,GEMINI_MODEL=gemini-2.5-flash,SCHEDULER_ENABLED=false,APP_CORS_ALLOWED_ORIGINS=https://arxivlens.vercel.app,APP_FRONTEND_BASE_URL=https://arxivlens.vercel.app,SYNC_NOTIFY_EMAIL=you@example.com \
  --set-secrets=SPRING_DATASOURCE_URL=ARXIVLENS_TIDB_URL:latest,SPRING_DATASOURCE_USERNAME=ARXIVLENS_TIDB_USERNAME:latest,SPRING_DATASOURCE_PASSWORD=ARXIVLENS_TIDB_PASSWORD:latest,JWT_SECRET=ARXIVLENS_JWT_SECRET:latest,GEMINI_API_KEY=ARXIVLENS_GEMINI_API_KEY:latest,RESEND_API_KEY=ARXIVLENS_RESEND_API_KEY:latest,CRON_TOKEN=ARXIVLENS_CRON_TOKEN:latest,GOOGLE_CLIENT_ID=ARXIVLENS_GOOGLE_CLIENT_ID:latest
```

`APP_CORS_ALLOWED_ORIGINS` / `APP_FRONTEND_BASE_URL` already point at the production Vercel URL above; if your Vercel URL differs, you'll correct it in step 3.7.

### 2.4 Note the URL and sanity-check

The deploy prints a **Service URL** like `https://arxivlens-backend-<hash>.asia-east1.run.app`. Verify it:

```bash
curl https://arxivlens-backend-<hash>.asia-east1.run.app/actuator/health   # → {"status":"UP"}
```

> With `--min-instances=0` the service scales to zero when idle, so the first request after a quiet period pays a brief cold start (a few seconds). The startup sync runner refreshes real arXiv / HBR data when the service starts.

### 2.5 Wire up auto-deploy on push (Cloud Build)

So you don't re-run the deploy command by hand, connect a **Cloud Build trigger** that runs the repo's `cloudbuild.yaml` (build → push to Artifact Registry → `gcloud run deploy --image` → prune old revisions) on every push to `main`:

1. Cloud Console → **Cloud Build → Triggers → Create trigger** (or `gcloud builds triggers create github …`).
2. Connect your GitHub repo, set the event to **push to branch** `^main$`.
3. Configuration: **Cloud Build configuration file**, location `cloudbuild.yaml` (repo root).
4. (Optional but recommended) set **Included files** to `backend/**,cloudbuild.yaml` so frontend-only pushes don't trigger a backend build.

Because the trigger's deploy step passes only `--image`, it keeps the env vars and secret bindings from step 2.3. To change runtime config later, run `gcloud run services update arxivlens-backend --region=asia-east1 --update-env-vars=… / --update-secrets=…` — **not** the CI pipeline.

## 3. Deploy the frontend (Vercel)

1. Sign up at <https://vercel.com> → connect GitHub → import the same repo.
2. **Root Directory**: `frontend` (this is critical — the project is a monorepo).
3. Framework: should auto-detect as **Next.js**.
4. **Environment Variables** → Add:

   | Key                      | Value                                                |
   | ------------------------ | ---------------------------------------------------- |
   | `NEXT_PUBLIC_API_URL`    | `https://arxivlens-backend-<hash>.asia-east1.run.app/api` (use the Service URL from step 2.4) |

   Apply the variable to **Production**, **Preview**, and **Development**. `NEXT_PUBLIC_*` vars are baked in at build time, so any later change to this URL requires a Vercel redeploy.
5. **Deploy**. First build ~2 min.
6. Once live, copy the Vercel URL: `https://arxivlens.vercel.app` (and the auto-generated preview pattern, e.g. `https://arxivlens-*.vercel.app`).
7. If your Vercel production URL differs from the `https://arxivlens.vercel.app` baked into step 2.3, update `APP_CORS_ALLOWED_ORIGINS` (and `APP_FRONTEND_BASE_URL`) on the Cloud Run service. Simplest value is just the production URL:
   ```bash
   gcloud run services update arxivlens-backend --region=asia-east1 \
     --update-env-vars=APP_CORS_ALLOWED_ORIGINS=https://arxivlens.vercel.app,APP_FRONTEND_BASE_URL=https://arxivlens.vercel.app
   ```
   If you also want PR preview URLs to work, list them comma-separated. Vercel's preview pattern is:
   ```
   https://arxivlens-git-<branch>-<vercel-team-slug>.vercel.app
   ```
   To find your exact team slug: Vercel dashboard → arxivlens project → Deployments → copy the host name from any preview deployment. Free Hobby accounts default to `<username>-projects`, e.g.:
   ```bash
   gcloud run services update arxivlens-backend --region=asia-east1 \
     --update-env-vars=^|^APP_CORS_ALLOWED_ORIGINS=https://arxivlens.vercel.app,https://arxivlens-git-main-greg-projects.vercel.app
   ```
   Comma-separated, no trailing slash, no whitespace. (The `^|^` prefix switches the delimiter to `|` so the commas inside the value aren't split into separate env vars.) The update rolls out a new revision in ~30 s.
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
2. **Cloud Run → set `APPLE_CLIENT_ID`:**
   ```bash
   gcloud run services update arxivlens-backend --region=asia-east1 \
     --update-env-vars=APPLE_CLIENT_ID=com.arxivlens.web
   ```
   (Comma-separate if you also support a native app bundle ID — use the `^|^` delimiter trick from step 3.7.) This rolls out a new revision automatically.
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

The backend refreshes arXiv every 6 hours and emails a summary when it finishes. Because Cloud Run **scales to zero** when idle (`min-instances=0`), the in-process `@Scheduled` job can't reliably fire at the cron times — so the schedule is driven by a free **external** trigger instead.

**How the pieces fit:**

- `POST /api/cron/arxiv-sync` — token-protected endpoint (no login needed) that runs the sync + sends the summary email. The request also wakes the scaled-to-zero service.
- A scheduled **GitHub Actions** workflow (`.github/workflows/sync-cron.yml`, already in the repo) POSTs to it every 6h.
- `EmailService` (Resend) sends the summary to `SYNC_NOTIFY_EMAIL`.

**Setup:**

1. **Backend → Cloud Run config.** These were already set in §2:
   - `CRON_TOKEN` — strong shared secret stored in Secret Manager as `ARXIVLENS_CRON_TOKEN` (step 2.2).
   - `SYNC_NOTIFY_EMAIL` — where to send the summary (blank disables the email); env var from step 2.3.
   - `SCHEDULER_ENABLED=false` — the external cron drives it now; avoids a duplicate run if an instance happens to be warm at a tick.

   Email delivery also needs `RESEND_API_KEY` (stored as `ARXIVLENS_RESEND_API_KEY`). With Resend's dev sandbox the recipient must be the address you registered with Resend.

2. **GitHub → repo → Settings → Secrets and variables → Actions:**
   - Add a repository **secret** `CRON_TOKEN` with the *same* value as the one in `ARXIVLENS_CRON_TOKEN` (read it with `gcloud secrets versions access latest --secret=ARXIVLENS_CRON_TOKEN`).
   - (Optional) a repository **variable** `BACKEND_URL` if your backend isn't at the default in the workflow (`https://arxivlens-backend-880984423210.asia-east1.run.app`).

   The workflow then runs at `00/06/12/18:00 UTC` (GitHub may delay a few minutes). Until `CRON_TOKEN` is set it skips with a warning instead of failing.

**Trigger / test it manually:**

- GitHub → **Actions** → *Scheduled source sync* → **Run workflow**, or `gh workflow run sync-cron.yml`, or
- direct (the workflow sends the token as the `X-Cron-Token` header):
  `curl -X POST -H "X-Cron-Token: <CRON_TOKEN>" "https://arxivlens-backend-<hash>.asia-east1.run.app/api/cron/arxiv-sync"` → `{"status":"started"}`, then a summary email arrives in ~1–2 min.
- To test only the **email** path (no sync), use the **Test sync email** button in Admin → Data Sources.

**Prefer a different scheduler?** Any cron service works — e.g. cron-job.org or Cloud Scheduler: POST that URL every 6h with timeout ≥30 s (for the cold-start wake). Alternatively set `SCHEDULER_ENABLED=true` and `--min-instances=1` to keep an instance warm, but that's billed continuously and gives up the scale-to-zero free-tier savings.

## 6. Updating

Push to `main`. Vercel and the Cloud Build trigger (step 2.5) both auto-redeploy:

```powershell
git push origin main
```

Vercel typically finishes in ~1 min; Cloud Build builds the backend image and deploys the new Cloud Run revision in ~3–5 min (Maven + Docker build). The CI deploy passes only `--image`, so your env vars and secret bindings are preserved across deploys.

## 7. Common gotchas

| Symptom                                                                  | Cause                                                                                | Fix                                                                                       |
| ------------------------------------------------------------------------ | ------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------- |
| Frontend loads but API calls all 401 / network error                     | CORS — `APP_CORS_ALLOWED_ORIGINS` doesn't include the exact Vercel URL               | Update the env var (no trailing slash) with `gcloud run services update …` — see §3.7     |
| Backend container fails to start / `out of memory` in Cloud Run logs     | 512 MiB is tight. Some startup spike pushes over.                                    | Bump `--memory=1Gi`, or lower the JVM `MaxRAMPercentage` (e.g. `-XX:MaxRAMPercentage=60`) |
| Cron sync runs but Latest stays empty / background work never finishes   | CPU was throttled after the HTTP response, freezing the background sync thread       | Ensure the service has `--no-cpu-throttling` (see §2.3)                                    |
| Login works but Latest is empty for a long time                          | First cold-start sync is still running, or the service had scaled to zero            | Refresh after 15 s. If still empty, check logs: `gcloud run services logs read arxivlens-backend --region=asia-east1` |
| 6-hourly sync / notification email never runs                            | Cloud Run scales to zero, so the in-process `@Scheduled` job doesn't fire            | Drive it externally — see §5 (set `CRON_TOKEN` + the GitHub Actions secret; `SCHEDULER_ENABLED=false`) |
| `Gemini 403: API_KEY_INVALID`                                            | Wrong / unenabled key                                                                | Regenerate key in AI Studio; key must come from `aistudio.google.com/apikey` (not Cloud Console) |
| `Gemini 400: User location is not supported`                             | Egress region geo-blocked by Google                                                  | `asia-east1` is supported; if you deployed elsewhere, redeploy in a supported region      |
| `Communications link failure` to TiDB                                    | Wrong region or missing TLS args                                                     | URL must include `useSSL=true&enabledTLSProtocols=TLSv1.2,TLSv1.3`                        |
| Cloud Build deploy fails / 403 on Secret Manager at startup              | Runtime SA can't read a secret, or a secret name is wrong                            | Re-run the `secretAccessor` bindings in §2.2; confirm `--set-secrets` names match         |

## 8. Things this setup intentionally does NOT do

- **No Flyway / Liquibase** — schema is managed by Hibernate `ddl-auto=update`. Fine for hobby, not for production with real users.
- **No backups** — TiDB Cloud Serverless free tier has a basic point-in-time recovery window, but no scheduled exports. If your data matters, export manually.
- **No CDN / image optimization** — Vercel handles it for the Next.js side; the backend serves PDFs directly from Cloud Run.
- **No custom domain** — both Vercel and Cloud Run let you map a domain (free), but DNS is on you.

## 9. Switching off / tearing down

| Platform     | Where                                                                |
| ------------ | -------------------------------------------------------------------- |
| Vercel       | Dashboard → project → Settings → Advanced → Delete Project          |
| Cloud Run    | `gcloud run services delete arxivlens-backend --region=asia-east1` (also delete the Cloud Build trigger + Artifact Registry images to stop all charges) |
| TiDB Cloud   | Dashboard → cluster → "…" → Pause Cluster (free tier auto-pauses too) |

Pausing TiDB drops the data; export `mysqldump` if you want to keep it.
