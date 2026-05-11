package com.arxivlens.bootstrap;

import com.arxivlens.dto.SyncDtos.SyncResult;
import com.arxivlens.entity.Source;
import com.arxivlens.repository.PaperRepository;
import com.arxivlens.repository.SourceRepository;
import com.arxivlens.service.sync.SyncDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Kicks off an arXiv + HBR sync right after the application is up so users see real
 * data on first login without ever clicking "Sync now". Replaces the old demo seed
 * papers, which carried fictitious titles.
 *
 * The sync runs on a background thread via {@link CompletableFuture#runAsync}: blocking
 * the main startup path on an external HTTP call (5–15 s for arXiv) would make the
 * server look hung. If the call fails (offline, rate limit, source disabled) the
 * exception is logged but the server still serves — the scheduler / "Sync now" can
 * recover later.
 *
 * {@code @Order(100)} keeps this strictly after {@link DataSeeder} (which has the
 * default {@code Ordered.LOWEST_PRECEDENCE}). The two are otherwise independent —
 * sources / topics come from {@code data.sql}.
 */
@Component
@Order(100)
public class StartupSyncRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSyncRunner.class);

    /**
     * Consider a source "shallow" — and therefore needing a backfill — if either
     * <ul>
     *   <li>row count is below this value (a fresh deploy that's only seen one sync), or</li>
     *   <li>the oldest row is younger than {@link #SHALLOW_SPAN_DAYS} days (a deeper-but-
     *       still-narrow dataset, e.g. one that was hit with the old firehose-paginated
     *       backfill which only reached back a few days).</li>
     * </ul>
     * Either condition alone is enough — the span check catches DBs that already
     * have plenty of rows but no historical breadth, which the count check alone misses.
     */
    private static final long SHALLOW_COUNT = 300L;
    private static final long SHALLOW_SPAN_DAYS = 180L;

    private final SyncDispatcher dispatcher;
    private final SourceRepository sources;
    private final PaperRepository papers;

    public StartupSyncRunner(SyncDispatcher dispatcher, SourceRepository sources, PaperRepository papers) {
        this.dispatcher = dispatcher;
        this.sources = sources;
        this.papers = papers;
    }

    @Override
    public void run(ApplicationArguments args) {
        CompletableFuture.runAsync(this::sync);
    }

    private void sync() {
        try {
            log.info("Initial sync: starting for all enabled sources");
            List<SyncResult> results = dispatcher.syncAllEnabled();
            for (SyncResult r : results) {
                if (r.error() != null) {
                    log.warn("Initial sync [{}] error: {}", r.sourceCode(), r.error());
                } else {
                    log.info("Initial sync [{}] fetched={} inserted={} skipped={}",
                            r.sourceCode(), r.fetched(), r.inserted(), r.skipped());
                }
            }
            log.info("Initial sync: done");

            maybeBackfill();
        } catch (Exception e) {
            log.warn("Initial sync failed (server is up; user can hit Sync now manually)", e);
        }
    }

    /**
     * Self-heals an empty / shallow DB: if any backfill-capable source has too
     * few rows or too short a span, runs a 12-month backfill so Trends has
     * meaningful per-month data without the admin needing to push a button.
     *
     * <p>Only looks at sources whose handler {@link com.arxivlens.service.sync.SourceSyncService#supportsBackfill() supports backfill}
     * (i.e. arXiv). HBR's RSS feed has no historical depth, so its row count
     * will always be "shallow" — checking it would re-trigger the loop every boot.
     */
    private void maybeBackfill() {
        try {
            Instant spanCutoff = Instant.now().minus(SHALLOW_SPAN_DAYS, ChronoUnit.DAYS);
            List<Source> enabled = sources.findByEnabledTrueOrderByDisplayOrderAsc();
            boolean any = false;
            for (Source s : enabled) {
                if (!dispatcher.supportsBackfill(s.getCode())) continue;
                long count = papers.countBySourceId(s.getId());
                Optional<Instant> oldest = papers.findOldestPublishedAtBySourceId(s.getId());
                boolean countShallow = count < SHALLOW_COUNT;
                boolean spanShallow = oldest.map(o -> o.isAfter(spanCutoff)).orElse(true);
                if (countShallow || spanShallow) {
                    any = true;
                    log.info("Source [{}] is shallow (count={}, oldest={}); triggering 12-month backfill",
                            s.getCode(), count, oldest.map(Instant::toString).orElse("none"));
                    break;
                }
            }
            if (!any) return;
            List<SyncResult> results = dispatcher.backfillAllEnabled(12);
            for (SyncResult r : results) {
                if (r.error() != null) {
                    log.warn("Backfill [{}] error: {}", r.sourceCode(), r.error());
                } else {
                    log.info("Backfill [{}] fetched={} inserted={} skipped={}",
                            r.sourceCode(), r.fetched(), r.inserted(), r.skipped());
                }
            }
        } catch (Exception e) {
            log.warn("Auto-backfill failed (admin can still POST /api/admin/backfill)", e);
        }
    }
}
