package com.arxivlens.service.sync;

import com.arxivlens.config.AppProperties;
import com.arxivlens.dto.SyncDtos.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final AppProperties props;
    private final SyncDispatcher dispatcher;

    @Value("${app.scheduler.enabled:false}")
    private boolean enabled;

    public SyncScheduler(AppProperties props, SyncDispatcher dispatcher) {
        this.props = props;
        this.dispatcher = dispatcher;
    }

    @Scheduled(cron = "${app.scheduler.arxiv-cron:0 0 */6 * * *}")
    public void arxiv() {
        if (!enabled) return;
        log.info("Scheduled arXiv sync starting");
        SyncResult r = dispatcher.syncByCode("arxiv");
        log.info("arXiv sync done: fetched={} inserted={} skipped={} error={}",
                r.fetched(), r.inserted(), r.skipped(), r.error());
    }

    @Scheduled(cron = "${app.scheduler.hbr-cron:0 30 */6 * * *}")
    public void hbr() {
        if (!enabled) return;
        log.info("Scheduled HBR sync starting");
        SyncResult r = dispatcher.syncByCode("hbr");
        log.info("HBR sync done: fetched={} inserted={} skipped={} error={}",
                r.fetched(), r.inserted(), r.skipped(), r.error());
    }

    // Business Weekly intentionally has no scheduled sync — it's a manual paste
    // source, same as HBR. Articles are added via the Feed page's "Add article"
    // button. Removed the auto-sync cron after the scraping attempts proved
    // unreliable; BW's search page markup varied too much to extract titles
    // consistently.

    @SuppressWarnings("unused")
    private AppProperties unusedRef() { return props; }
}
