package com.arxivlens.service;

import com.arxivlens.config.AppProperties;
import com.arxivlens.dto.AuthDtos.ForgotPasswordRequest;
import com.arxivlens.dto.AuthDtos.ResetPasswordRequest;
import com.arxivlens.entity.PasswordResetToken;
import com.arxivlens.entity.User;
import com.arxivlens.repository.PasswordResetTokenRepository;
import com.arxivlens.repository.UserRepository;
import com.arxivlens.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Password reset flow.
 *
 * <ol>
 *   <li>{@link #requestReset} — generate a single-use token, persist its SHA-256
 *       hash, email the raw token in a reset link. Returns 200 regardless of
 *       whether the email exists (anti-enumeration; never reveal which addresses
 *       are registered).</li>
 *   <li>{@link #resetPassword} — hash the supplied token, look up the row,
 *       check expiration + not-yet-used, update {@code password_hash} and mark
 *       the token used. 400 on invalid/expired/used tokens.</li>
 * </ol>
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final int TOKEN_BYTES = 32;  // 256 bits → 64 hex chars

    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PasswordEncoder encoder;
    private final EmailService email;
    private final AppProperties props;

    public PasswordResetService(UserRepository users,
                                PasswordResetTokenRepository tokens,
                                PasswordEncoder encoder,
                                EmailService email,
                                AppProperties props) {
        this.users = users;
        this.tokens = tokens;
        this.encoder = encoder;
        this.email = email;
        this.props = props;
    }

    /**
     * Always returns success — the caller (and HTTP response) cannot distinguish
     * "email not registered" from "email sent". This prevents enumeration of
     * registered accounts via the forgot-password endpoint.
     */
    @Transactional
    public void requestReset(ForgotPasswordRequest req) {
        Optional<User> match = users.findByEmail(req.email());
        if (match.isEmpty()) {
            log.info("Password reset requested for unregistered email — silently ignoring");
            return;
        }
        User user = match.get();
        if (user.getPasswordHash() == null) {
            // OAuth-only account — no password to reset. Same silent success.
            log.info("Password reset requested for OAuth-only account {}", user.getEmail());
            return;
        }

        String rawToken = generateToken();
        String tokenHash = sha256Hex(rawToken);
        long expMin = props.passwordReset() == null || props.passwordReset().expirationMinutes() <= 0
                ? 30L
                : props.passwordReset().expirationMinutes();

        PasswordResetToken row = new PasswordResetToken();
        row.setUserId(user.getId());
        row.setTokenHash(tokenHash);
        row.setExpiresAt(Instant.now().plus(expMin, ChronoUnit.MINUTES));
        tokens.save(row);

        sendResetEmail(user.getEmail(), rawToken, expMin);
    }

    /**
     * Applies the new password if the token is valid (not expired, not used).
     * Throws {@link ApiException} 400 otherwise — the frontend renders a generic
     * "link invalid or expired" message.
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest req) {
        String tokenHash = sha256Hex(req.token());
        PasswordResetToken row = tokens.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Invalid or expired reset link"));

        if (row.getUsedAt() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This reset link has already been used");
        }
        if (row.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This reset link has expired");
        }

        User user = users.findById(row.getUserId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Invalid or expired reset link"));

        user.setPasswordHash(encoder.encode(req.password()));
        users.save(user);

        row.setUsedAt(Instant.now());
        tokens.save(row);

        log.info("Password reset succeeded for user {}", user.getEmail());
    }

    private void sendResetEmail(String to, String rawToken, long expMin) {
        String base = props.frontend() == null || props.frontend().baseUrl() == null || props.frontend().baseUrl().isBlank()
                ? "http://localhost:3000"
                : stripTrailingSlash(props.frontend().baseUrl());
        String link = base + "/reset-password?token=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
        String body = """
                Hi,

                We received a request to reset the password for your arxivLens account.
                Click the link below to choose a new password. The link expires in %d minutes.

                %s

                If you didn't request this, you can safely ignore this email — your password
                will remain unchanged.

                — arxivLens
                """.formatted(expMin, link);
        email.sendPlainText(to, "Reset your arxivLens password", body);
    }

    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RNG.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK spec — this can't happen.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
