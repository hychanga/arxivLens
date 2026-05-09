package com.arxivlens.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.arxivlens.entity.Download;
import com.arxivlens.entity.Paper;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public final class DownloadDtos {

    private DownloadDtos() {}

    public record CreateDownloadRequest(
            @NotNull Long paperId
    ) {}

    public record DownloadView(
            Long id,
            Paper paper,
            String filePath,
            @JsonProperty("sizeMB") Double sizeMb,
            Instant downloadedAt
    ) {
        public static DownloadView of(Download d) {
            return new DownloadView(
                    d.getId(),
                    d.getPaper(),
                    d.getFilePath(),
                    d.getSizeMb(),
                    d.getDownloadedAt()
            );
        }
    }
}
