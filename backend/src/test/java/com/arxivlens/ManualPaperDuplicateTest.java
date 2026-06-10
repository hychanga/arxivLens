package com.arxivlens;

import com.arxivlens.dto.PaperDtos.ManualPaperRequest;
import com.arxivlens.dto.PaperDtos.ManualPaperResponse;
import com.arxivlens.entity.Source;
import com.arxivlens.repository.SourceRepository;
import com.arxivlens.service.PaperService;
import com.arxivlens.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the manual-add duplicate guard: a re-add of the same article (by
 * URL, or by case-insensitive title) is rejected with a 409 carrying the
 * machine-readable code the frontend localizes on.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ManualPaperDuplicateTest {

    @Autowired
    private PaperService paperService;

    @Autowired
    private SourceRepository sources;

    private Long sourceId;

    @BeforeEach
    void seedSource() {
        Source s = new Source();
        s.setCode("dup-test-src"); // unique — avoids the default sources seeded at startup
        s.setName("Duplicate Test Source");
        s.setEnabled(true);
        s.setDisplayOrder(0);
        sourceId = sources.save(s).getId();
    }

    private ManualPaperRequest req(String title, String url) {
        return new ManualPaperRequest(sourceId, title, "Some article body.", url, null, null, null);
    }

    @Test
    void rejectsSameUrl() {
        paperService.createManual(req("Strategy in 2026", "https://hbr.org/2026/06/strategy"));

        // Same URL, different title -> blocked on URL.
        assertThatThrownBy(() ->
                paperService.createManual(req("A completely different title", "https://hbr.org/2026/06/strategy")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("DUPLICATE_URL");
                });
    }

    @Test
    void rejectsSameTitleCaseInsensitive() {
        paperService.createManual(req("Strategy in 2026", null));

        // Same title, different case, no URL -> blocked on title.
        assertThatThrownBy(() ->
                paperService.createManual(req("STRATEGY IN 2026", null)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("DUPLICATE_TITLE");
                });
    }

    @Test
    void allowsDistinctArticle() {
        paperService.createManual(req("Strategy in 2026", "https://hbr.org/2026/06/strategy"));

        ManualPaperResponse saved =
                paperService.createManual(req("Leadership in 2026", "https://hbr.org/2026/06/leadership"));
        assertThat(saved.id()).isNotNull();
    }
}
