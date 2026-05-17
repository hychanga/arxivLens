package com.arxivlens.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public final class PreferenceDtos {

    private PreferenceDtos() {}

    /**
     * @param keywords map keyed by source code (e.g. {@code "arxiv"}, {@code "hbr"})
     *                 to the user's keyword list for that source. Order in each list
     *                 is priority (#1 highest weight).
     */
    public record PreferenceResponse(
            Integer queryDays,
            String sortMode,
            Map<String, List<String>> keywords,
            Long currentSourceId,
            Integer perPage
    ) {}

    public record PreferenceUpdateRequest(
            // 0 = "All" sentinel (no upper bound on publishedAt — we have rows
            // going back to 2009, older than any reasonable day-count cap).
            // 1..3650 = "last N days" with the same ceiling PaperService uses.
            @Min(0) @Max(3650) Integer queryDays,
            @Size(max = 32) String sortMode,
            Map<String, List<@Size(max = 64) String>> keywords,
            Long currentSourceId,
            @Min(1) @Max(100) Integer perPage
    ) {}
}
