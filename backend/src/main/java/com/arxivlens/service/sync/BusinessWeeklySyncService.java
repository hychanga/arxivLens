package com.arxivlens.service.sync;

import com.arxivlens.dto.SyncDtos.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Business Weekly is a manual paste source.
 *
 * <p>The earlier attempt scraped {@code /Search?keyword=...} and then each
 * article page individually, but the search-results page returned different
 * (and inconsistent) markup depending on user agent / cookies, so we kept
 * losing articles. Mirroring the HBR model — user reads on businessweekly.com.tw
 * themselves, pastes the text into the Feed page — is the right shape.
 *
 * <p>The dispatcher still calls {@link #sync()} (via the scheduler / admin
 * "Sync now"); it just returns a no-op result so the dispatcher doesn't bail.
 */
@Service
public class BusinessWeeklySyncService implements SourceSyncService {

    private static final Logger log = LoggerFactory.getLogger(BusinessWeeklySyncService.class);

    @Override
    public String sourceCode() {
        return "businessweekly";
    }

    @Override
    public SyncResult sync() {
        log.info("Business Weekly auto-sync is intentionally a no-op — articles are added manually via the Feed page.");
        return new SyncResult(sourceCode(), 0, 0, 0,
                "Business Weekly is a manual source — paste articles from the Feed page (no auto-sync).");
    }
}
