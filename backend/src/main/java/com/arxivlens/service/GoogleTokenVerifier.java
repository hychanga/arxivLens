package com.arxivlens.service;

import com.arxivlens.config.AppProperties;
import com.arxivlens.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates a Google Identity Services ID token using Google's public
 * {@code tokeninfo} endpoint. The endpoint verifies signature, issuer, and
 * expiry server-side; we additionally check that {@code aud} matches our
 * configured client ID (so a token minted for some other app can't be
 * replayed against us).
 *
 * <p>Why tokeninfo instead of the {@code google-auth-library-oauth2-http}
 * Java library: this is a hobby app with hobby-tier sign-in volume. The
 * library adds ~5 transitive deps to the JAR for what amounts to one
 * HTTP call per login, and Google explicitly OKs tokeninfo for low-volume
 * use. If sign-in traffic ever justifies it, swap to {@code GoogleIdTokenVerifier}
 * which caches Google's JWKS locally and verifies signatures offline.
 */
@Service
public class GoogleTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleTokenVerifier.class);
    private static final String TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo?id_token=";
    /** Google ID tokens may use either form for the {@code iss} claim. */
    private static final Set<String> VALID_ISSUERS = Set.of(
            "accounts.google.com",
            "https://accounts.google.com");

    private final AppProperties props;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public GoogleTokenVerifier(AppProperties props) {
        this.props = props;
    }

    public record VerifiedClaims(String subject, String email, String displayName, boolean emailVerified) {}

    public VerifiedClaims verify(String idToken) {
        String clientId = clientId();
        if (clientId == null || clientId.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Google sign-in is not configured on this server (missing GOOGLE_CLIENT_ID).");
        }
        if (idToken == null || idToken.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Missing Google ID token");
        }

        HttpResponse<String> res;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(TOKENINFO_URL + URLEncoder.encode(idToken, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("Could not reach Google tokeninfo: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not contact Google to verify your sign-in. Try again.");
        }

        if (res.statusCode() != 200) {
            log.warn("Google tokeninfo rejected token: HTTP {} body={}", res.statusCode(), res.body());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Google sign-in token is invalid or expired.");
        }

        String body = res.body();
        String iss = extractString(body, "iss");
        String aud = extractString(body, "aud");
        String sub = extractString(body, "sub");
        String email = extractString(body, "email");
        String name = extractString(body, "name");
        // tokeninfo returns email_verified as a string "true"/"false" (unlike the JWT itself
        // where it's a JSON boolean).
        String emailVerifiedStr = extractString(body, "email_verified");

        if (!VALID_ISSUERS.contains(iss)) {
            log.warn("Google tokeninfo bad iss: {}", iss);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Google sign-in token has the wrong issuer.");
        }
        if (!clientId.equals(aud)) {
            log.warn("Google tokeninfo aud mismatch: expected={} got={}", clientId, aud);
            throw new ApiException(HttpStatus.UNAUTHORIZED,
                    "Google sign-in token was minted for a different application.");
        }
        if (sub == null || sub.isBlank() || email == null || email.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED,
                    "Google sign-in token is missing required claims.");
        }

        return new VerifiedClaims(sub, email, name, "true".equalsIgnoreCase(emailVerifiedStr));
    }

    private String clientId() {
        if (props.oauth() == null || props.oauth().google() == null) return null;
        return props.oauth().google().clientId();
    }

    /**
     * Extract a JSON string field's value. Google's tokeninfo response is a flat
     * object with no nested structures in the fields we care about, so a regex
     * on {@code "key":"value"} is reliable and dependency-free (we don't have
     * jackson-databind on the compile classpath in our starter set).
     */
    private static final Pattern STRING_FIELD = Pattern.compile(
            "\"([^\"]+)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private static String extractString(String json, String key) {
        Matcher m = STRING_FIELD.matcher(json);
        while (m.find()) {
            if (key.equals(m.group(1))) {
                return unescapeBasic(m.group(2));
            }
        }
        return null;
    }

    /** Just enough JSON-string unescaping for the well-formed values Google returns. */
    private static String unescapeBasic(String s) {
        return s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\/", "/")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }
}
