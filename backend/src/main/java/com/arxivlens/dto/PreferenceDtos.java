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
            @Min(1) @Max(365) Integer queryDays,
            @Size(max = 32) String sortMode,
            Map<String, List<@Size(max = 64) String>> keywords,
            Long currentSourceId,
            @Min(1) @Max(100) Integer perPage
    ) {}
}
