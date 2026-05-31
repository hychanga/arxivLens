package com.arxivlens.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(
    name = "papers",
    uniqueConstraints = @UniqueConstraint(name = "uk_papers_source_external", columnNames = {"source_id", "external_id"})
)
@Getter
@Setter
@NoArgsConstructor
public class Paper {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private Source source;

    @Column(name = "source_id", insertable = false, updatable = false)
    private Long sourceId;

    @Column(name = "external_id", nullable = false, length = 190)
    private String externalId;

    @Column(nullable = false, length = 512)
    private String title;

    /** JSON array of author display names. */
    @Column(name = "authors_json", nullable = false, columnDefinition = "JSON")
    private String authorsJson;

    /** Wire name is "abstract" — see arxivLens-requirements §11. */
    @Lob
    @Column(name = "abstract_text", nullable = false, columnDefinition = "TEXT")
    @JsonProperty("abstract")
    private String abstractText;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String introduction;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String conclusion;

    @Column(length = 512)
    private String url;

    @Column(name = "pdf_url", length = 512)
    private String pdfUrl;

    /** Wire name is "pages" — see arxivLens-requirements §11. */
    @Column(name = "page_count")
    @JsonProperty("pages")
    private Integer pageCount;

    @Column(name = "topic_code", length = 64)
    private String topicCode;

    /**
     * Every arXiv category the paper is tagged with (primary + cross-lists),
     * stored comma-delimited with leading/trailing commas as match delimiters —
     * e.g. {@code ,cs.LG,cs.AI,stat.ML,}. {@code topicCode} keeps just the
     * primary for display/grouping; this field lets the feed match a paper that
     * cross-lists into a topic without being primarily filed there (arXiv's
     * {@code cat:} search behaves the same way). Null for rows synced before
     * this column existed and for manual/HBR papers; the feed falls back to
     * {@code topicCode} in that case.
     */
    @JsonIgnore
    @Column(name = "categories", length = 512)
    private String categories;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @CreationTimestamp
    @Column(name = "fetched_at", nullable = false, updatable = false)
    private Instant fetchedAt;
}
