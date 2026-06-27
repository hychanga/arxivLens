package com.arxivlens.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class GolfDtos {
    private GolfDtos() {}

    public record GolfResourceRequest(
        @NotBlank @Size(max = 256) String title,
        String summary,
        String content,
        @Size(max = 64) String category,
        @Size(max = 1024) String tags,
        @Size(max = 512) String videoUrl,
        @Size(max = 512) String pdfUrl,
        @Size(max = 512) String source
    ) {}
}
