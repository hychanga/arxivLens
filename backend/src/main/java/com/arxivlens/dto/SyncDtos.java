package com.arxivlens.dto;

public final class SyncDtos {

    private SyncDtos() {}

    public record SyncResult(
            String sourceCode,
            int fetched,
            int inserted,
            int skipped,
            String error
    ) {}
}
