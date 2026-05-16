package com.arxivlens.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * The actual PDF bytes for a {@link Download}. Stored in a sibling table rather
 * than as a column on {@code Download} so that list queries (Library page,
 * counts, etc.) don't accidentally load multi-megabyte BLOBs they don't need —
 * Hibernate's reliable lazy-loading for {@code byte[]} columns requires
 * bytecode enhancement, and we prefer not to depend on that.
 *
 * <p>Why DB BLOB and not the filesystem: Render Free's filesystem is
 * ephemeral, so PDFs written to {@code ${user.dir}/var/...} vanish on every
 * cold start / redeploy. TiDB persists, so the bytes survive restarts. The
 * tradeoff is roughly 2–5 MB of TiDB quota per paper.
 */
@Entity
@Table(name = "download_blobs")
@Getter
@Setter
@NoArgsConstructor
public class DownloadBlob {

    /**
     * Same value as the parent {@link Download#getId()}. Not declared as a JPA
     * relationship to keep the entity standalone — {@code DownloadService}
     * coordinates lifecycle (insert blob right after inserting the parent,
     * delete blob right before deleting the parent).
     */
    @Id
    @Column(name = "download_id")
    private Long downloadId;

    @Lob
    @Column(name = "pdf_data", nullable = false, columnDefinition = "LONGBLOB")
    private byte[] pdfData;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
