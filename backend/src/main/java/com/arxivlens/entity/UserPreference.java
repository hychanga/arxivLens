package com.arxivlens.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "user_preferences")
@Getter
@Setter
@NoArgsConstructor
public class UserPreference {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @JsonIgnore
    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "query_days", nullable = false)
    private Integer queryDays = 7;

    @Column(name = "sort_mode", nullable = false, length = 32)
    private String sortMode = "NEWEST";

    /**
     * JSON array of keyword strings; **order is priority** (#1 highest).
     * Stored as a MySQL JSON column.
     */
    @Column(name = "keywords_json", columnDefinition = "JSON")
    private String keywordsJson;

    @Column(name = "current_source_id")
    private Long currentSourceId;

    @Column(name = "per_page", nullable = false)
    private Integer perPage = 10;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
