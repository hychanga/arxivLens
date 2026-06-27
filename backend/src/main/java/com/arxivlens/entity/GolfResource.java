package com.arxivlens.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.Instant;

@Entity
@Table(name = "golf_resources")
@Getter @Setter @NoArgsConstructor
public class GolfResource {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 256)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(length = 64)
    private String category;

    @Column(length = 1024)
    private String tags;

    @Column(name = "video_url", length = 512)
    private String videoUrl;

    @Column(name = "pdf_url", length = 512)
    private String pdfUrl;

    @Column(length = 512)
    private String source;

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
