package com.arxivlens.service;

import com.arxivlens.dto.TrendDtos.MonthBucket;
import com.arxivlens.dto.TrendDtos.MonthCount;
import com.arxivlens.dto.TrendDtos.Metrics;
import com.arxivlens.dto.TrendDtos.TopicBreakdown;
import com.arxivlens.dto.TrendDtos.TrendsResponse;
import com.arxivlens.repository.PaperRepository;
import com.arxivlens.repository.SourceRepository;
import com.arxivlens.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class TrendService {

    private final PaperRepository papers;
    private final SourceRepository sources;

    public TrendService(PaperRepository papers, SourceRepository sources) {
        this.papers = papers;
        this.sources = sources;
    }

    @Transactional(readOnly = true)
    public TrendsResponse compute(String sourceCode) {
        long sourceId = sources.findByCode(sourceCode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Unknown source: " + sourceCode))
                .getId();

        Instant since = Instant.now().minus(365, ChronoUnit.DAYS);
        List<Object[]> rows = papers.aggregateMonthlyByTopic(sourceId, since);

        // Map<yearMonth, Map<topicCode, count>>
        TreeMap<String, Map<String, Long>> monthIndex = new TreeMap<>();
        Map<String, Long> totalsByTopic = new HashMap<>();

        for (Object[] r : rows) {
            String topic = r[0] == null ? "(uncategorized)" : r[0].toString();
            String ym = r[1].toString();
            long count = ((Number) r[2]).longValue();
            monthIndex.computeIfAbsent(ym, k -> new LinkedHashMap<>()).merge(topic, count, Long::sum);
            totalsByTopic.merge(topic, count, Long::sum);
        }

        // Build full 12-month series so the chart x-axis is regular.
        YearMonth ym = YearMonth.now(ZoneOffset.UTC).minusMonths(11);
        List<MonthBucket> months = new ArrayList<>();
        long total = 0;
        String peakMonth = null;
        long peakTotal = -1;
        for (int i = 0; i < 12; i++) {
            String key = ym.toString();
            Map<String, Long> byTopic = monthIndex.getOrDefault(key, Map.of());
            long monthTotal = byTopic.values().stream().mapToLong(Long::longValue).sum();
            months.add(new MonthBucket(key, byTopic, monthTotal));
            total += monthTotal;
            if (monthTotal > peakTotal) { peakTotal = monthTotal; peakMonth = key; }
            ym = ym.plusMonths(1);
        }

        // Per-topic last 5 months breakdown.
        List<TopicBreakdown> topics = new ArrayList<>();
        List<MonthBucket> last5 = months.subList(Math.max(0, months.size() - 5), months.size());
        totalsByTopic.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed())
                .forEach(e -> {
                    String topic = e.getKey();
                    List<MonthCount> recent = last5.stream()
                            .map(b -> new MonthCount(b.yearMonth(), b.byTopic().getOrDefault(topic, 0L)))
                            .toList();
                    topics.add(new TopicBreakdown(topic, e.getValue(), recent));
                });

        double avg = total / 12.0;
        Metrics m = new Metrics(total, Math.round(avg * 10.0) / 10.0,
                peakMonth == null ? "-" : peakMonth, totalsByTopic.size());

        return new TrendsResponse(m, months, topics);
    }
}
