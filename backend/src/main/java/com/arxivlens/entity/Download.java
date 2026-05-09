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
    name = "downloads",
    uniqueConstraints = @UniqueConstraint(name = "uk_dl_user_paper", columnNames = {"user_id", "paper_id"})
)
@Getter
@Setter
@NoArgsConstructor
public class Download {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "user_id", insertable = false, updatable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "paper_id", nullable = false)
    private Paper paper;

    @Column(name = "file_path", nullable = false, length = 512)
    private String filePath;

    /** Wire name is "sizeMB" — see arxivLens-requirements §11. */
    @Column(name = "size_mb", nullable = false)
    @JsonProperty("sizeMB")
    private Double sizeMb;

    @CreationTimestamp
    @Column(name = "downloaded_at", nullable = false, updatable = false)
    private Instant downloadedAt;
}
