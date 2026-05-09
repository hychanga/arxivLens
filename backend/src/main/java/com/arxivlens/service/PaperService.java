package com.arxivlens.service;

import com.arxivlens.entity.Paper;
import com.arxivlens.repository.PaperRepository;
import com.arxivlens.repository.SourceRepository;
import com.arxivlens.web.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@Transactional(readOnly = true)
public class PaperService {

    private final PaperRepository papers;
    private final SourceRepository sources;

    public PaperService(PaperRepository papers, SourceRepository sources) {
        this.papers = papers;
        this.sources = sources;
    }

    public Page<Paper> findFeed(String sourceCode, Integer days, String topicCode, int page, int size) {
        long sourceId = sources.findByCode(sourceCode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Unknown source: " + sourceCode))
                .getId();
        int safeDays = (days == null) ? 30 : Math.min(365, Math.max(1, days));
        Instant since = Instant.now().minus(safeDays, ChronoUnit.DAYS);
        int safeSize = Math.min(100, Math.max(1, size));
        return papers.findFeed(
                sourceId,
                since,
                (topicCode == null || topicCode.isBlank()) ? null : topicCode,
                PageRequest.of(Math.max(0, page), safeSize, Sort.by(Sort.Direction.DESC, "publishedAt"))
        );
    }
}
