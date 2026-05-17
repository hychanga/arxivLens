package com.arxivlens.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Server-side cache of inline article images that come back through the
 * extractor. We proxy + cache because:
 *
 * <ul>
 *   <li>Some publisher CDNs (商業週刊's ibw.bwnet.com.tw, for example) ship
 *       Referer-based hotlink protection — a direct {@code <img src>} from our
 *       origin would just 403.</li>
 *   <li>The publisher might rotate URLs or remove images later; caching the
 *       bytes once means the saved article is still readable.</li>
 * </ul>
 *
 * <p>One row per source URL. {@code urlHash} is the SHA-256 of the URL (hex,
 * 64 chars) so we can index a fixed-width key instead of paying the cost of a
 * unique index on a long VARCHAR. The full URL is kept alongside for logging
 * and admin inspection.
 */
@Entity
@Table(
    name = "cached_images",
    indexes = @Index(name = "ix_cached_images_url_hash", columnList = "url_hash", unique = true)
)
@Getter
@Setter
@NoArgsConstructor
public class CachedImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SHA-256(url) as a 64-char lowercase hex string. */
    @Column(name = "url_hash", nullable = false, length = 64)
    private String urlHash;

    @Column(name = "source_url", nullable = false, length = 2048)
    private String sourceUrl;

    /** e.g. "image/jpeg", "image/png", "image/webp". */
    @Column(name = "content_type", length = 64)
    private String contentType;

    @Lob
    @Column(name = "data", nullable = false, columnDefinition = "LONGBLOB")
    private byte[] data;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
