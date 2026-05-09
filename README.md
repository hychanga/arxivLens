# arxivLens

Full-stack application with a **Next.js** frontend and a **Spring Boot** backend.

## Stack

| Layer    | Tech                                    |
| -------- | --------------------------------------- |
| Frontend | Next.js (App Router, TypeScript, Tailwind) |
| Backend  | Spring Boot 4.0.6, Java 25, Maven       |
| DB (dev) | H2 (in-memory)                          |
| Container| Docker Compose                          |

## Layout

```
arxivLens/
├── frontend/          # Next.js app
├── backend/           # Spring Boot app (Maven)
├── .claude/skills/    # Project-level Claude Code skills
├── docker-compose.yml
└── README.md
```

## Local development

### Frontend
```powershell
cd frontend
npm install
npm run dev          # http://localhost:3000
```

### Backend
```powershell
cd backend
.\mvnw spring-boot:run   # http://localhost:8080
```

H2 console (dev): http://localhost:8080/h2-console

### Docker (both)
```powershell
docker compose up --build
```
> Requires Docker Desktop. `Dockerfile` is not yet generated for either service — add one in each folder before running this.

## Project-level Claude Code skills

Located at `.claude/skills/`:

| Skill              | Purpose                                                       |
| ------------------ | ------------------------------------------------------------- |
| `init`             | Generate or refresh `CLAUDE.md`                                |
| `review`           | Code review of branch / PR changes                             |
| `security-review`  | Security audit of branch changes                               |
| `simplify`         | Apply quality / dedup fixes to the current branch              |

These are project-customizable copies — edit each `SKILL.md` to fit arxivLens conventions.
