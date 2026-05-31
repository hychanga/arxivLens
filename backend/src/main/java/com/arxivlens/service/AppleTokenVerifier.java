package com.arxivlens.service;

import com.arxivlens.web.ApiException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Locator;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.ProtectedHeader;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.JwkSet;
import io.jsonwebtoken.security.Jwks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.Key;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a "Sign in with Apple" identity token.
 *
 * <p>Unlike Google (which exposes a {@code tokeninfo} endpoint we can call to
 * have the token checked server-side), Apple offers no such helper — we must
 * verify the JWS signature ourselves against Apple's published public keys.
 * The flow:
 *
 * <ol>
 *   <li>Fetch Apple's JWK Set from {@value #JWKS_URL} (cached in-memory, see
 *       below) and index the public keys by their {@code kid}.</li>
 *   <li>Verify the token's RS256 signature using the key whose {@code kid}
 *       matches the token header. JJWT also enforces {@code exp}/{@code nbf}
 *       during parsing.</li>
 *   <li>Manually assert {@code iss == https://appleid.apple.com} and that the
 *       token's {@code aud} contains one of our configured Services IDs, so a
 *       token minted for another app can't be replayed against us.</li>
 * </ol>
 *
 * <p>Apple rotates its signing keys, so the JWKS is cached with a TTL and
 * re-fetched on demand when a token references a {@code kid} we haven't seen
 * (handles rotation without a fixed polling job).
 */
@Service
public class AppleTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(AppleTokenVerifier.class);
    private static final String JWKS_URL = "https://appleid.apple.com/auth/keys";
    private static final String APPLE_ISSUER = "https://appleid.apple.com";
    private static final Duration CACHE_TTL = Duration.ofHours(6);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Public verification keys indexed by {@code kid}. Refreshed lazily; see {@link #keys(boolean)}. */
    private volatile Map<String, Key> cachedKeys = Map.of();
    private volatile Instant cachedAt = Instant.EPOCH;

    public record VerifiedClaims(String subject, String email, boolean emailVerified) {}

    /**
     * @param idToken   the {@code id_token} the frontend received from Apple
     * @param audiences allowed values for the token's {@code aud} claim (our
     *                  configured Services ID(s)); must be non-empty — callers
     *                  decide whether Apple sign-in is configured before calling
     */
    public VerifiedClaims verify(String idToken, List<String> audiences) {
        if (idToken == null || idToken.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Missing Apple identity token");
        }

        Claims claims;
        try {
            claims = parse(idToken, keys(false));
        } catch (JwtException first) {
            // The token may reference a freshly-rotated key we haven't cached, or
            // our cache may be stale. Refresh once and retry before giving up.
            try {
                claims = parse(idToken, keys(true));
            } catch (JwtException second) {
                log.warn("Apple identity token failed verification: {}", second.getMessage());
                throw new ApiException(HttpStatus.UNAUTHORIZED, "Apple sign-in token is invalid or expired.");
            }
        }

        if (!APPLE_ISSUER.equals(claims.getIssuer())) {
            log.warn("Apple token bad iss: {}", claims.getIssuer());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Apple sign-in token has the wrong issuer.");
        }
        Set<String> aud = claims.getAudience();
        if (aud == null || audiences.stream().noneMatch(aud::contains)) {
            log.warn("Apple token aud mismatch: expected one of {} got {}", audiences, aud);
            throw new ApiException(HttpStatus.UNAUTHORIZED,
                    "Apple sign-in token was minted for a different application.");
        }

        String sub = claims.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Apple sign-in token is missing required claims.");
        }
        String email = claims.get("email", String.class);
        // Apple sends email_verified as either a JSON boolean or a string "true"/"false"
        // depending on the flow; normalise both shapes.
        boolean emailVerified = "true".equalsIgnoreCase(String.valueOf(claims.get("email_verified")));

        return new VerifiedClaims(sub, email, emailVerified);
    }

    private Claims parse(String idToken, Map<String, Key> keysByKid) {
        Locator<Key> keyLocator = header -> {
            String kid = header instanceof ProtectedHeader ph ? ph.getKeyId() : null;
            return kid == null ? null : keysByKid.get(kid);
        };
        return Jwts.parser()
                .keyLocator(keyLocator)
                .clockSkewSeconds(60)
                .build()
                .parseSignedClaims(idToken)
                .getPayload();
    }

    private Map<String, Key> keys(boolean forceRefresh) {
        if (forceRefresh || cachedKeys.isEmpty() || Instant.now().isAfter(cachedAt.plus(CACHE_TTL))) {
            refresh();
        }
        return cachedKeys;
    }

    private synchronized void refresh() {
        String body;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(JWKS_URL))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + res.statusCode());
            }
            body = res.body();
        } catch (Exception e) {
            log.warn("Could not fetch Apple JWKS: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not contact Apple to verify your sign-in. Try again.");
        }

        JwkSet set = Jwks.setParser().build().parse(body);
        Map<String, Key> next = new HashMap<>();
        for (Jwk<?> jwk : set.getKeys()) {
            if (jwk.getId() != null) {
                next.put(jwk.getId(), jwk.toKey());
            }
        }
        cachedKeys = Map.copyOf(next);
        cachedAt = Instant.now();
    }
}
