package com.arxivlens.service.sync;

import com.arxivlens.dto.SyncDtos.SyncResult;

public interface SourceSyncService {

    /** Stable code identifying the source (e.g. "arxiv", "hbr"). */
    String sourceCode();

    /** Fetches & upserts papers. Never throws — wraps any error in {@link SyncResult#error()}. */
    SyncResult sync();
}
