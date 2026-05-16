package com.arxivlens.service.sync;

import com.arxivlens.dto.SyncDtos.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * HBR is now a manual paste source.
 *
 * <p>Previously this class scraped HBR's per-topic RSS feeds for headlines + teasers.
 * Two problems with that path:
 * <ul>
 *   <li>The RSS feed only ships paywall teasers. The full article body is behind
 *       a subscriber login that we can't honor from a backend HTTP call.</li>
 *   <li>Even if we could, scraping subscriber content would violate HBR's ToS.</li>
 * </ul>
 *
 * <p>The right model for HBR is "the user reads the article themselves, then pastes
 * the text into arxivLens for translation / summarization / library archival".
 * That's what {@code POST /api/papers/manual} does. The scheduler still calls
 * {@link #sync()} every 6 h, but it's intentionally a no-op so the scheduler
 * doesn't bail.
 */
@Service
public class HbrSyncService implements SourceSyncService {

    private static final Logger log = LoggerFactory.getLogger(HbrSyncService.class);

    @Override
    public String sourceCode() {
        return "hbr";
    }

    @Override
    public SyncResult sync() {
        log.info("HBR auto-sync is intentionally a no-op — articles are added manually via the Feed page.");
        return new SyncResult(sourceCode(), 0, 0, 0,
                "HBR is a manual source — paste articles from the Feed page (no auto-sync).");
    }
}
