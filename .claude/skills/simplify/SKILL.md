---
name: simplify
description: Review changed code on the current branch for reuse, clarity, and efficiency, then apply the fixes. Run after a feature is working but before opening a PR.
---

# simplify — arxivLens

Look at the changes you just made and improve them — without changing behavior. The goal is "what survives review", not "what works on first try".

## Process

1. **Diff against `main`.** Read every changed file fully, not just the hunks.
2. **Hunt for these patterns:**
   - **Duplication.** Same logic in two places → extract once. But: 3 similar lines is fine; don't abstract on first repeat.
   - **Reinvention.** Logic that an existing util / library already does. Search the repo before writing helpers.
   - **Dead code.** Unused imports, vars, branches, params. Remove rather than comment-out.
   - **Over-engineering.** Premature interfaces, single-impl factories, config flags for things that have one value, error handling for cases that can't happen.
   - **Awkward shapes.** Nested ternaries, deep `if/else`, mutable accumulators where a `map`/`filter`/`reduce` would read clearer (and vice versa — sometimes a `for` loop is clearer).
   - **Inefficient I/O.** N+1 queries (JPA), waterfall fetches in React effects, sync work that should be async.
   - **Inconsistency.** Naming, layering, or error handling that doesn't match neighbors.
3. **Apply the fixes.** This skill *does* edit. After each change, re-run the relevant check (`npm run lint`, `./mvnw test`) to confirm behavior is preserved.
4. **Stop when the marginal change isn't worth it.** Simplification has diminishing returns — a 5-line refactor that saves nothing is noise.

## What NOT to do

- Don't rewrite code that's already fine just to put your stamp on it.
- Don't add comments to explain code that good naming would already explain.
- Don't introduce new abstractions to "prepare for the future" — wait until the second use case shows up.
- Don't touch files outside the diff scope. If you find issues elsewhere, mention them; don't fix them.

## Output

After applying changes, summarize:
- What you changed and why (one bullet per change).
- What you considered but rejected, and why.
- Anything you noticed but left for the human (e.g., a cross-cutting concern that needs design input).
