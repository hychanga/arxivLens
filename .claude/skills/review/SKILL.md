---
name: review
description: Review the pending changes on the current branch (or a referenced PR) for correctness, design, and consistency with arxivLens conventions. Use before opening or merging a PR.
---

# review — arxivLens

Perform a code review on the current branch's changes vs. the base branch, or on a specified PR.

## Inputs

- No argument → review uncommitted + committed changes on the current branch vs. `main`.
- `<PR number or URL>` → fetch the PR via `gh pr view` / `gh pr diff` and review that.

## Process

1. **Get the diff.** Use `git diff` (local) or `gh pr diff` (PR). Also list changed files with `git diff --name-only` so you can read full file context where the diff alone is misleading.
2. **Read enough surrounding code** to understand the change in context — at least the function and its callers. Don't review snippets in isolation.
3. **Check by category:**
   - **Correctness** — logic bugs, off-by-one, null/undefined, error paths, race conditions.
   - **API contracts** — frontend calls to backend: do request/response shapes match? Status codes handled? Auth headers preserved?
   - **State & side effects** — React rerenders, Spring transaction boundaries, JPA cascade surprises.
   - **Tests** — new behavior must have tests; tests must actually exercise the new code path.
   - **Consistency** — matches existing patterns in `frontend/` and `backend/` (naming, layering, error handling).
   - **Security** — only flag obvious issues here; defer deep review to the `security-review` skill.
4. **Run static checks** if cheap: `npm run lint` in frontend, `./mvnw -q -DskipTests verify` in backend. Note failures.

## Output

Group findings under:
- **Must fix** — bugs or contract breaks.
- **Should fix** — design / consistency issues.
- **Nits** — style, naming, minor cleanup.
- **Questions** — things the author should clarify.

For each finding, cite the file and line (`frontend/app/foo.tsx:42`) and propose a concrete change. No vague "consider refactoring" comments.

## Constraints

- Do not auto-fix. The skill reviews; the human decides what to apply.
- If the diff is huge (>500 lines), say so up front and review by area rather than line-by-line.
