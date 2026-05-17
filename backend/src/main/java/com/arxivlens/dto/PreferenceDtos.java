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
            // Upper bound mirrors PaperService.MAX_FEED_DAYS — keep them aligned
            // so the "2yr" / "All" quick filters in the sidebar can actually be
            // saved (the lower 365 cap silently 400'd the PATCH and left the DB
            // at the old value, which then snapped the slider back to 30 on the
            // next preferences refresh).
            @Min(1) @Max(3650) Integer queryDays,
            @Size(max = 32) String sortMode,
            Map<String, List<@Size(max = 64) String>> keywords,
            Long currentSourceId,
            @Min(1) @Max(100) Integer perPage
    ) {}
}
