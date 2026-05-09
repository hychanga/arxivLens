package com.arxivlens.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public final class AdminDtos {

    private AdminDtos() {}

    public record SettingsView(
            Integer defaultDays,
            Integer maxResultsPerSync,
            Integer autoRefreshIntervalMinutes
    ) {}

    public record UpdateSettingsRequest(
            @Min(1)  @Max(365)  Integer defaultDays,
            @Min(1)  @Max(2000) Integer maxResultsPerSync,
            @Min(15) @Max(1440) Integer autoRefreshIntervalMinutes
    ) {}
}
