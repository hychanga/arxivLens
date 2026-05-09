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
   - Region: pick the same region you will set on Render (e.g. `Singapore (ap-southeast-1)`).
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
5. Create the application database:
   - In the TiDB Cloud UI, open the SQL Editor, run:
     ```sql
     CREATE DATABASE arxivlens CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
     ```
6. Build the JDBC URL the backend will use (replace `<HOST>`, `<USER>`, `<PASS>`):
   ```
   jdbc:mysql://<HOST>:4000/arxivlens?useSSL=true&requireSSL=true&enabledTLSProtocols=TLSv1.2,TLSv1.3&serverTimezone=UTC&characterEncoding=utf8
   ```

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
5. When the service shows `Live`, note its URL: `https://arxivlens-backend-XXXX.onrender.com`.
6. Sanity check: `curl https://arxivlens-backend-XXXX.onrender.com/actuator/health` → `{"status":"UP"}`.

> The free tier sleeps after 15 min idle. The first request after sleep returns in ~30 s. The startup sync runner will refresh real arXiv / HBR data each time the service wakes.

## 3. Deploy the frontend (Vercel)

1. Sign up at <https://vercel.com> → connect GitHub → import the same repo.
2. **Root Directory**: `frontend` (this is critical — the project is a monorepo).
3. Framework: should auto-detect as **Next.js**.
4. **Environment Variables** → Add:

   | Key                      | Value                                                |
   | ------------------------ | ---------------------------------------------------- |
   | `NEXT_PUBLIC_API_URL`    | `https://arxivlens-backend-XXXX.onrender.com/api`    |

   Apply the variable to **Production**, **Preview**, and **Development**.
5. **Deploy**. First build ~2 min.
6. Once live, copy the Vercel URL: `https://arxivlens.vercel.app` (and the auto-generated preview pattern, e.g. `https://arxivlens-*.vercel.app`).
7. Go back to **Render → arxivlens-backend → Environment** and set:
   ```
   APP_CORS_ALLOWED_ORIGINS=https://arxivlens.vercel.app,https://arxivlens-git-main-yourname.vercel.app
   ```
   Comma-separated list, no trailing slash, no whitespace. Save → Render redeploys (~1 min).

## 4. First-time bootstrap

1. Open `https://arxivlens.vercel.app`.
2. Login with the demo account: `demo@arxivlens.local` / `demo123` (auto-seeded by `DataSeeder`).
3. Wait ~10 s for the first arXiv sync to finish (cold start). Refresh the Latest panel — real papers appear.
4. As the admin (`admin@arxivlens.local` / `admin123`), you can:
   - **Change passwords** — currently the demo passwords are static. The fastest path is to register a fresh account via the Login → Register tab and discard the demo accounts.
   - Hit **Sync now** in the Admin panel if the auto-sync hasn't fired yet.

## 5. Updating

Push to `main`. Vercel and Render both auto-redeploy:

```powershell
git push origin main
```

Vercel typically finishes in ~1 min, Render in ~5 min (Docker rebuild).

## 6. Common gotchas

| Symptom                                                                  | Cause                                                                                | Fix                                                                                       |
| ------------------------------------------------------------------------ | ------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------- |
| Frontend loads but API calls all 401 / network error                     | CORS — `APP_CORS_ALLOWED_ORIGINS` doesn't include the exact Vercel URL               | Add the URL (no trailing slash) and restart Render service                                |
| Backend health check fails / `out of memory` in Render logs              | 512 MB is tight. Some startup spike pushes over.                                     | Lower `MaxRAMPercentage` to 60, switch to `-XX:+UseSerialGC` (already set in render.yaml) |
| Login works but Latest is empty for a long time                          | First wake-up sync is still running, or Render slept                                 | Refresh after 15 s. If still empty, check Render logs for sync errors                     |
| `Gemini 403: API_KEY_INVALID`                                            | Wrong / unenabled key                                                                | Regenerate key in AI Studio; key must come from `aistudio.google.com/apikey` (not Cloud Console) |
| `Communications link failure` to TiDB                                    | Wrong region or missing TLS args                                                     | URL must include `useSSL=true&enabledTLSProtocols=TLSv1.2,TLSv1.3`                        |
| Render build fails: `Cannot find symbol DataSeeder constructor argument` | Old artifact compiled before the seeder change                                       | Force rebuild from Render → service → Manual Deploy → Clear build cache & deploy          |

## 7. Things this setup intentionally does NOT do

- **No Flyway / Liquibase** — schema is managed by Hibernate `ddl-auto=update`. Fine for hobby, not for production with real users.
- **No backups** — TiDB Cloud Serverless free tier has a basic point-in-time recovery window, but no scheduled exports. If your data matters, export manually.
- **No CDN / image optimization** — Vercel handles it for the Next.js side; backend serves PDFs directly which is slow over Render free.
- **No custom domain** — both Vercel and Render let you bring a domain (free), but DNS is on you.

## 8. Switching off / tearing down

| Platform     | Where                                                                |
| ------------ | -------------------------------------------------------------------- |
| Vercel       | Dashboard → project → Settings → Advanced → Delete Project          |
| Render       | Dashboard → service → Settings → Delete Service                     |
| TiDB Cloud   | Dashboard → cluster → "…" → Pause Cluster (free tier auto-pauses too) |

Pausing TiDB drops the data; export `mysqldump` if you want to keep it.
