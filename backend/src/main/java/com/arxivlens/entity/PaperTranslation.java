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

/**
 * Cached translation of a {@link Paper}'s title + abstract for a given locale.
 * One row per (paper, locale). Generated lazily on first request, then reused.
 */
@Entity
@Table(
    name = "paper_translations",
    uniqueConstraints = @UniqueConstraint(name = "uk_translation_paper_locale", columnNames = {"paper_id", "locale"})
)
@Getter
@Setter
@NoArgsConstructor
public class PaperTranslation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "paper_id", nullable = false)
    private Paper paper;

    @Column(name = "paper_id", insertable = false, updatable = false)
    private Long paperId;

    @Column(nullable = false, length = 10)
    private String locale;

    @Column(length = 512)
    private String title;

    /** Wire name is "abstract" to match the Paper entity convention. */
    @Lob
    @Column(name = "abstract_text", columnDefinition = "TEXT")
    @JsonProperty("abstract")
    private String abstractText;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
