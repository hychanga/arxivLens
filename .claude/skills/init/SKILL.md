---
name: init
description: Initialize or refresh the CLAUDE.md file at the repo root with up-to-date documentation about arxivLens (Next.js frontend + Spring Boot backend). Run when CLAUDE.md is missing, stale, or after major architectural changes.
---

# init — arxivLens

Bootstrap or refresh `CLAUDE.md` at the project root so future Claude Code sessions can quickly understand arxivLens.

## Steps

1. **Survey the repo.** Read top-level files (`README.md`, `docker-compose.yml`, `package.json`, `pom.xml`) and walk one level into `frontend/` and `backend/`. Note runtimes, build tools, and entry points.
2. **Capture conventions.** Look for ESLint/Prettier/Tailwind config, Java code style files, test directories, and any `.editorconfig`. Record what exists rather than what should exist.
3. **List the common workflows.** Dev server, build, test, lint, format, container start — for both `frontend/` and `backend/`. Pull the exact commands from `package.json` scripts and Maven goals.
4. **Identify glue points.** How frontend talks to backend (proxy? CORS? base URL env var?), shared types or contracts, and the docker-compose service graph.
5. **Write `CLAUDE.md`** at the project root. Sections to include:
   - Project summary (1–2 sentences)
   - Stack & versions (Next.js, Spring Boot, Java, Node)
   - Directory map (concise — only top 2 levels)
   - How to run (dev / build / test / lint) for each side
   - Conventions worth respecting
   - Anything tricky a newcomer would trip on

## Constraints

- **Do not invent.** If you cannot find something, omit it rather than guess.
- **Keep it tight.** Aim for under 150 lines. CLAUDE.md is loaded into every session; bloat costs context.
- **No tutorials.** Skip generic Next.js / Spring Boot explanations — link to upstream docs instead.
- If `CLAUDE.md` already exists, diff your draft against it and only update sections that are stale; preserve any project-specific notes the user added.
