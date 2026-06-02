---
name: resilient-sync
description: >-
  Build or harden a job that ingests data from an external / third-party API
  into a database — pagination, rate-limit backoff, idempotent upserts, short
  per-page transactions, deadlock retry, concurrency guards, and incremental
  watermarks. TRIGGER when the user adds or debugs a sync / import / crawler /
  feed-fetcher / backfill that pulls from an upstream API (arXiv, RSS, REST,
  etc.) and persists it, or reports sync errors (rate limit, deadlock, partial
  data, duplicate rows, "session flushed after an exception").
---

# Resilient external-API → database sync

Distilled from debugging a real arXiv ingester (Spring Boot + TiDB/MySQL). The
**principles and failure modes** are stack-agnostic; translate the snippets.

## The non-negotiables
1. **Idempotent.** Re-running must never duplicate or corrupt. Dedup by the
   upstream's stable external ID; `upsert`, don't blind-insert.
2. **Incremental watermark.** Track a per-source/per-topic "last synced" marker;
   query the upstream for "changed since marker". Advance it **only on success**.
   Provide a "reset marker to now − N days" path for deep backfills.
3. **Short transactions, never one giant one.** See §3 — this is the bug that
   bites hardest.
4. **Polite + resilient to the upstream.** Honor its rate guidance; retry
   transient failures with backoff; never let one page kill the whole run.
5. **One run at a time.** A scheduled run and a manual run colliding will
   deadlock. Guard it.

## 1. Pagination loop
Page until a short page (drained) or a safety cap. Persist each page as you go so
an interruption leaves valid partial progress.
```
start = 0; pages = 0
while pages < MAX_PAGES:
    page = fetchWithRetry(query, start, PAGE_SIZE)   # network, no DB tx held
    persistPage(page)                                 # its own short tx (§3)
    if len(page) < PAGE_SIZE: break                   # window drained
    start += PAGE_SIZE; pages += 1
    sleep(MIN_INTERVAL)                               # e.g. arXiv asks >=3s
advanceWatermark(now)                                 # only after the loop succeeds
```

## 2. Rate limits & timeouts (don't abandon the whole job)
- Respect the upstream's **minimum interval between requests** (arXiv: ≥3s) —
  and apply it **between pages**, not just between sources. A deep backfill is
  many pages; 1s spacing trips 429s, especially on a shared PaaS egress IP.
- Wrap each fetch in **bounded retry with exponential backoff** for `429` and
  request timeouts (e.g. 5s→30s, ~4 attempts). Without it, one 429 propagates
  and drops every remaining page of that source.
- Raise the per-request timeout for large pages served slowly under throttling.

## 3. Transactions — the #1 footgun
**Do NOT annotate the whole multi-page / multi-source `sync()` `@Transactional`.**
That single long transaction causes three distinct failures:
- **Deadlocks** — it holds write locks on thousands of rows for minutes, so it
  deadlocks against any concurrent writer (TiDB/MySQL error 1213 / SQLState
  40001).
- **All-or-nothing rollback** — once any statement errors, the tx is
  rollback-only; a failure on source B silently rolls back source A's
  already-fetched rows (they "vanish" despite a success log).
- **Hibernate `AssertionFailure: … null identifier … session flushed after an
  exception`** — when a deadlock-retry re-flushes the poisoned persistence
  context.

Fix: persist **each page (and each watermark update) in its own short
transaction** via a programmatic `TransactionTemplate`, with deadlock retry.
```java
int[] persistPage(page) {
  return withDeadlockRetry(() -> tx.execute(s -> upsertAll(page)));  // 1213/40001 → retry
}
```
**Critical caveat (open-session-in-view):** on a web **request thread**, Spring's
open-EntityManager-in-view binds ONE session for the whole request, so per-page
`TransactionTemplate` calls share (and can poison/bloat) it. Run long syncs on a
**background thread** (scheduler / executor) where each tx gets a fresh,
auto-closed EntityManager. Also: don't hold a DB connection while waiting on the
network — fetch outside the tx, persist inside it.

## 4. Concurrency guard
Serialize syncs so a scheduled run and a manual trigger can't overlap:
```java
if (!running.compareAndSet(false, true)) return SKIPPED;
try { ...doSync()... } finally { running.set(false); }
```
(One in-memory `AtomicBoolean` is enough for a single instance.)

## 5. Idempotent upsert + cheap re-runs
- **Batched existence check**, not find-per-row: collect the page's external IDs,
  query `WHERE external_id IN (:ids)` in chunks (≤500 to stay under
  `max_allowed_packet`), insert the misses.
- **Update existing rows' changed/new columns** too (don't just skip them) — so
  a re-sync can backfill a newly-added column onto historical rows.

## 6. Data-model gotcha: classification / tags
If items carry multiple categories (primary + cross-listed) and you filter by
category, **store ALL of them**, not just the primary — otherwise a filter on
category X misses items primarily filed elsewhere but cross-listed into X. Store
a delimited list (`,X,Y,Z,`) and match with `LIKE '%,X,%'`, keeping the primary
for display. Mark cross-list hits in the UI to avoid confusion.

## 7. Counts vs. stored rows
A "totals/trends" number sourced from the upstream's reported count (e.g.
`opensearch:totalResults`) will exceed what you've stored if you cap or filter on
ingest. Decide deliberately whether each metric reflects **upstream reality** or
**what you ingested**, and label it. Date semantics differ too (submitted-date vs
updated-date, calendar-month vs rolling-window) — don't compare across them.

## 8. Verify after a sync
Check the per-source log line shows `fetched/inserted/skipped` with no rate-limit
/ deadlock / AssertionFailure warnings, and that the visible count moved as
expected. If a "successful" fetch didn't change the data, suspect §3
(everything rolled back together).
