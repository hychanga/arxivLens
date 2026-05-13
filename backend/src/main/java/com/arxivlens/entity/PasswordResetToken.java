package com.arxivlens.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Single-use token issued by {@code PasswordResetService.requestReset}. The
 * raw token is emailed to the user; only its SHA-256 hash is persisted, so a
 * read-only DB leak doesn't grant password-reset ability.
 *
 * <p>Token lifecycle:
 * <ol>
 *   <li>User submits email → service generates a random token, stores its hash,
 *       emails the raw token in a link to the user.</li>
 *   <li>User clicks link → service hashes the supplied token, looks up the row,
 *       checks {@code expires_at > now} and {@code used_at IS NULL}, applies the
 *       new password, and sets {@code used_at = now}.</li>
 * </ol>
 *
 * <p>Note: rows are not deleted after use — keeping them lets us reject replays
 * of the same token (used_at NOT NULL) and gives a small audit trail. A periodic
 * cleanup job can prune expired rows, but it isn't required for correctness.
 */
@Entity
@Table(
    name = "password_reset_tokens",
    indexes = {
        @Index(name = "ix_prt_token_hash", columnList = "token_hash", unique = true),
        @Index(name = "ix_prt_user_id", columnList = "user_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** SHA-256 hex of the raw token (64 chars). */
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Non-null once the token has been redeemed, NULL while still valid. */
    @Column(name = "used_at")
    private Instant usedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
