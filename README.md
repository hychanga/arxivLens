# arxivLens

A curated paper feed for arXiv and Harvard Business Review with AI summaries, per-source keyword scoring, and on-demand translation into Traditional Chinese, Simplified Chinese, and Japanese.

> **Live demo:** <https://arxivlens.vercel.app> · API: <https://arxivlens-backend-880984423210.asia-east1.run.app/actuator/health>
>
> Demo logins: `demo@arxivlens.local / demo123` · `admin@arxivlens.local / admin123`
>
> *(Backend on Cloud Run with min-instances=0 — the first request after an idle period pays a brief cold start.)*

## What it does

- **Latest** — pulls papers from arXiv (Atom feed, every 6 h) and HBR (RSS) into a unified feed; filter by topic, days, and author/abstract keyword relevance scoring.
- **Favorites** — save papers, attach notes, generate AI summaries on demand (Gemini 2.5 Flash).
- **Library** — cache PDFs locally, open them from a built-in viewer.
- **Trends** — 12-month stacked bar by topic + per-topic 5-month breakdown.
- **Admin** — toggle sources / topics, manage settings, trigger sync.
- **Multi-language** — UI + paper title/abstract translation into zh-TW / zh-CN / ja, with per-paper translation cache.
- **Auth** — email/password + mock Google/Apple OAuth (frontend-only); JWT in `localStorage`.

## Tech stack

| Layer    | Tech                                                       |
| -------- | ---------------------------------------------------------- |
| Frontend | Next.js 16 (App Router) · React 19 · TypeScript · Tailwind 4 · Zustand |
| Backend  | Spring Boot 4.0.6 · Java 25 · Maven · Spring Security · JJWT |
| Database | MySQL 8 (local dev via Docker) · TiDB Cloud Serverless (prod) |
| AI       | Google Gemini 2.5 Flash via [AI Studio](https://aistudio.google.com) |
| Hosting  | Vercel (frontend) · Google Cloud Run (backend, `asia-east1`) · TiDB Cloud (database) |

## Quick start (local)

You'll need: **Java 25 (JDK)**, **Node 22+**, **Docker Desktop** (for MySQL), **Maven wrapper** (bundled).

```powershell
# 1. Clone
git clone <your-fork-url> arxivLens
cd arxivLens

# 2. Start MySQL via docker-compose
docker compose up mysql -d

# 3. Backend
cd backend
$env:JWT_SECRET    = "please-change-me-this-must-be-at-least-32-bytes-long-for-HS256"
$env:GEMINI_API_KEY = ""    # optional; leave blank to disable AI features
.\mvnw spring-boot:run      # → http://localhost:8080

# 4. Frontend (new terminal)
cd frontend
npm install
npm run dev                 # → http://localhost:3000
```

Open <http://localhost:3000> and log in with `demo@arxivlens.local / demo123`.

## Deploy your own copy

See **[DEPLOY.md](./DEPLOY.md)** for the full Vercel + Cloud Run + TiDB Cloud walkthrough (~30 min, on free tiers).

## Layout

```
arxivLens/
├── frontend/                     # Next.js 16 (App Router)
│   ├── app/(app)/                #   Authed shell — Latest / Favorites / Library / Trends / Admin
│   ├── app/login/                #   Login + OAuth tabs
│   ├── components/               #   Shared UI (PaperCard, KeywordEditor, modals, …)
│   ├── store/                    #   Zustand stores (one per resource)
│   └── lib/i18n.ts               #   en / zh-TW / zh-CN / ja translation dictionary
├── backend/                      # Spring Boot 4 (Java 25)
│   ├── src/main/java/com/arxivlens/
│   │   ├── controller/           #   REST endpoints under /api/*
│   │   ├── service/              #   Business logic + AI client + sync services
│   │   ├── entity/               #   JPA entities
│   │   ├── repository/           #   Spring Data JPA
│   │   ├── security/             #   JWT filter, SecurityConfig
│   │   └── bootstrap/            #   DataSeeder + StartupSyncRunner
│   ├── src/main/resources/
│   │   ├── application.properties      # base / dev defaults
│   │   ├── application-prod.properties # production profile (no DROP TABLE, Hibernate update)
│   │   └── schema.sql + data.sql       # dev-only schema bootstrap
│   └── Dockerfile
├── docker-compose.yml            # MySQL (+ optional backend / frontend) for local dev
├── cloudbuild.yaml               # Cloud Build CI — build + deploy backend image to Cloud Run on push to main
├── DEPLOY.md                     # Full deploy guide
└── arxivLens-requirements.md     # Original product spec
```

## Configuration

All runtime config is environment-variable driven. The complete list:

| Env var                       | Purpose                                                                | Default (local)         |
| ----------------------------- | ---------------------------------------------------------------------- | ----------------------- |
| `SPRING_DATASOURCE_URL`       | JDBC URL to MySQL/TiDB                                                 | `jdbc:mysql://localhost:3306/arxivlens?...` |
| `SPRING_DATASOURCE_USERNAME`  | DB user                                                                 | `arxivlens`             |
| `SPRING_DATASOURCE_PASSWORD`  | DB password                                                             | `arxivlens`             |
| `JWT_SECRET`                  | HMAC key for issuing JWTs (≥ 32 bytes)                                  | dev placeholder         |
| `GEMINI_API_KEY`              | <https://aistudio.google.com/apikey>; leave blank to disable AI         | empty                   |
| `GOOGLE_CLIENT_ID`            | Google Identity Services Web client ID; blank → mock Google sign-in     | empty                   |
| `APPLE_CLIENT_ID`             | Apple Services ID (token `aud`); blank → mock Apple sign-in. See DEPLOY.md | empty                 |
| `GEMINI_MODEL`                | Model name                                                             | `gemini-2.5-flash`      |
| `APP_CORS_ALLOWED_ORIGINS`    | Comma-separated allow-list for browser origins                          | `http://localhost:3000` |
| `SCHEDULER_ENABLED`           | In-process `@Scheduled` 6 h auto-sync. Off in prod — Cloud Run scales to zero, so GitHub Actions drives the cron (see DEPLOY.md §5) | `false`                 |
| `SPRING_PROFILES_ACTIVE`      | `prod` switches off schema.sql + uses Hibernate `ddl-auto=update`       | dev profile             |
| `PORT`                        | HTTP listen port (Cloud Run injects this automatically)                 | `8080`                  |

## Security note

- Demo credentials are intentionally weak (`admin123` / `demo123`) — change them or register a fresh account before sharing.
- `JWT_SECRET` must be ≥ 32 bytes in production. `openssl rand -hex 32` is fine.
- Don't commit `*Config.txt`, `.env`, or anything containing DB credentials. The `.gitignore` blocks the common patterns.

## Acknowledgements

- arXiv API (free public API, no key required).
- Harvard Business Review topic RSS feeds.
- Google AI Studio (Gemini) free tier for AI summaries + translations.
