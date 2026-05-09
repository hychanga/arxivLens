---
name: security-review
description: Audit the pending changes on the current branch for security issues — injection, auth/authz gaps, secrets, unsafe deserialization, dependency risk, and CORS/CSRF misconfiguration. Run before merging anything that touches auth, input handling, or external I/O.
---

# security-review — arxivLens

Focused security review of the changes on the current branch (or a referenced PR). Complement to the general `review` skill — this one only looks at security.

## Scope

Both sides of the stack:
- **Frontend (Next.js):** XSS via `dangerouslySetInnerHTML`, unsafe `eval`, leaked env vars (anything not prefixed `NEXT_PUBLIC_` must not appear in client code), open redirects, missing `rel="noopener noreferrer"`, client-side secrets in network calls.
- **Backend (Spring Boot):** SQL/JPQL injection, mass assignment via `@RequestBody`, missing `@PreAuthorize` on sensitive endpoints, unsafe deserialization, path traversal in file handling, SSRF in outbound HTTP, weak crypto, hardcoded credentials, overly permissive CORS, disabled CSRF on state-changing endpoints, actuator endpoints exposed without auth.
- **Cross-cutting:** Secrets in code or config, dependency vulns (`npm audit`, `mvn dependency-check` if available), Docker image base, exposed ports in `docker-compose.yml`, log statements that print tokens / PII.

## Process

1. List changed files: `git diff --name-only main...HEAD`.
2. Read each one fully — security bugs hide outside the diff window.
3. For each finding, record: **file:line**, **category** (e.g., "SQLi", "auth gap"), **severity** (critical / high / medium / low / info), **why it's exploitable**, and **a concrete fix**.
4. If the change touches auth, run through the OWASP ASVS Level 1 checklist for that endpoint.
5. Check for new dependencies — flag any that are unmaintained, low-download, or known-vulnerable.

## Output

Order findings by severity, highest first. Each entry:
- **[Severity] Title** — `path:line`
- *Issue:* one or two sentences.
- *Fix:* concrete code change or config update.

End with a short summary: count by severity, plus any blanket recommendations (e.g., "enable Spring Security on /actuator/**").

## Constraints

- No false positives if you can verify them away by reading the code. If unsure, say "unverified — please confirm" rather than flagging blindly.
- Do not propose blanket "use a WAF" or "enable rate limiting" unless the change actually warrants it.
- This skill is read-only. Suggest fixes; don't apply them.
