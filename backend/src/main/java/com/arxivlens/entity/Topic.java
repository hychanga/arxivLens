package com.arxivlens.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(
    name = "topics",
    uniqueConstraints = @UniqueConstraint(name = "uk_topic_source_code", columnNames = {"source_id", "code"})
)
@Getter
@Setter
@NoArgsConstructor
public class Topic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private Source source;

    @Column(name = "source_id", insertable = false, updatable = false)
    private Long sourceId;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "is_enabled", nullable = false)
    private Boolean enabled = Boolean.TRUE;

    /**
     * Last time the arXiv sync successfully queried this topic. The next sync
     * uses this as the lower bound of {@code submittedDate:[…]} so we only
     * pull papers that arrived since — categories that haven't been touched
     * (i.e. no new arXiv submissions) yield a tiny no-op response instead of
     * the previous "always re-fetch the top N globally" behaviour. Null on a
     * freshly enabled topic; the sync falls back to a default lookback window.
     */
    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;
}
