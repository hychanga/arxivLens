package com.arxivlens.service;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * Time-based One-Time Password (RFC 6238 / Google Authenticator compatible).
 *
 * <p>Self-contained — no third-party TOTP / Base32 dependency. The secret is
 * 20 bytes (160 bits, the RFC 4226 recommendation) encoded as Base32 because
 * that's what Authenticator apps expect on the {@code otpauth://} URI.
 *
 * <p>Verification accepts the previous, current, and next time step (±30 s of
 * clock skew). Anything tighter rejects users whose phone clock is slightly
 * off; anything looser opens a replay window.
 */
@Service
public class TotpService {

    private static final String ALGO = "HmacSHA1";
    private static final int SECRET_BYTES = 20;
    private static final int CODE_DIGITS = 6;
    private static final int STEP_SECONDS = 30;
    private static final int SKEW_STEPS = 1;

    private final SecureRandom rng = new SecureRandom();

    /** Generates a fresh 160-bit secret, Base32-encoded. */
    public String generateSecretBase32() {
        byte[] bytes = new byte[SECRET_BYTES];
        rng.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /**
     * Builds the {@code otpauth://totp/...} URI that Authenticator apps scan
     * via QR code. {@code label} typically formats as
     * {@code "{issuer}:{account}"} (e.g. {@code "arxivLens:admin@…"}); the
     * issuer query param duplicates that for older clients.
     */
    public String buildOtpauthUri(String issuer, String account, String secretBase32) {
        String label = issuer + ":" + account;
        return "otpauth://totp/" + urlEncode(label)
                + "?secret=" + secretBase32
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + CODE_DIGITS + "&period=" + STEP_SECONDS;
    }

    /** True if {@code code} is the 6-digit token for {@code secret} now (±1 step). */
    public boolean verify(String secretBase32, String code) {
        if (secretBase32 == null || secretBase32.isBlank()) return false;
        if (code == null || !code.matches("\\d{" + CODE_DIGITS + "}")) return false;
        long now = Instant.now().getEpochSecond() / STEP_SECONDS;
        byte[] key;
        try {
            key = base32Decode(secretBase32);
        } catch (Exception e) {
            return false;
        }
        for (long t = now - SKEW_STEPS; t <= now + SKEW_STEPS; t++) {
            if (computeCode(key, t).equals(code)) return true;
        }
        return false;
    }

    private static String computeCode(byte[] key, long timestep) {
        try {
            byte[] data = ByteBuffer.allocate(8).putLong(timestep).array();
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(key, ALGO));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int code = binary % (int) Math.pow(10, CODE_DIGITS);
            return String.format("%0" + CODE_DIGITS + "d", code);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    // --- Base32 (RFC 4648) — minimal, lower-case Base32 alphabet trimmed of padding. ---

    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder((data.length + 4) / 5 * 8);
        int buffer = 0, bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                out.append(B32.charAt((buffer >> bitsLeft) & 0x1F));
            }
        }
        if (bitsLeft > 0) {
            out.append(B32.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return out.toString();
    }

    private static byte[] base32Decode(String s) {
        String clean = s.replace("=", "").replace(" ", "").toUpperCase();
        byte[] out = new byte[clean.length() * 5 / 8];
        int buffer = 0, bitsLeft = 0, idx = 0;
        for (int i = 0; i < clean.length(); i++) {
            int v = B32.indexOf(clean.charAt(i));
            if (v < 0) throw new IllegalArgumentException("Bad Base32 char at " + i);
            buffer = (buffer << 5) | v;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                out[idx++] = (byte) ((buffer >> bitsLeft) & 0xFF);
            }
        }
        return out;
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
