package com.arxivlens.dto;

import java.util.List;
import java.util.Map;

public final class TrendDtos {

    private TrendDtos() {}

    public record TrendsResponse(
            Metrics metrics,
            List<MonthBucket> months,
            List<TopicBreakdown> topics
    ) {}

    public record Metrics(
            long totalPapers,
            double monthlyAverage,
            String peakMonth,
            int activeTopicCount
    ) {}

    /** Per month, the count for each topic_code in the response. */
    public record MonthBucket(
            String yearMonth,        // e.g. "2026-04"
            Map<String, Long> byTopic,
            long total
    ) {}

    /** Past 5 months for a single topic — used for the horizontal-bar breakdown. */
    public record TopicBreakdown(
            String topicCode,
            long total,
            List<MonthCount> recent5Months
    ) {}

    public record MonthCount(String yearMonth, long count) {}
}
