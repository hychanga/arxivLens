package com.arxivlens.service;

import com.arxivlens.dto.AuthDtos.AuthResponse;
import com.arxivlens.dto.AuthDtos.LoginRequest;
import com.arxivlens.dto.AuthDtos.RegisterRequest;
import com.arxivlens.dto.AuthDtos.TwoFactorEnableRequest;
import com.arxivlens.dto.AuthDtos.TwoFactorSetupResponse;
import com.arxivlens.dto.AuthDtos.TwoFactorStatusResponse;
import com.arxivlens.dto.AuthDtos.UserSummary;
import com.arxivlens.config.AppProperties;
import com.arxivlens.entity.User;
import com.arxivlens.repository.UserRepository;
import com.arxivlens.security.JwtService;
import com.arxivlens.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Service
public class AuthService {

    /** Error code returned on 401 when password is OK but OTP is missing/wrong. */
    public static final String CODE_OTP_REQUIRED = "OTP_REQUIRED";
    public static final String CODE_OTP_INVALID = "OTP_INVALID";

    /** Issuer label that shows up in Authenticator apps for our otpauth URIs. */
    private static final String OTP_ISSUER = "arxivLens";

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final GoogleTokenVerifier googleVerifier;
    private final AppleTokenVerifier appleVerifier;
    private final TotpService totp;
    private final AppProperties props;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt,
                       GoogleTokenVerifier googleVerifier, AppleTokenVerifier appleVerifier,
                       TotpService totp, AppProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.googleVerifier = googleVerifier;
        this.appleVerifier = appleVerifier;
        this.totp = totp;
        this.props = props;
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (users.existsByEmail(req.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "Email already registered");
        }
        User u = new User();
        u.setEmail(req.email());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setDisplayName(req.displayName());
        u.setRole("USER");
        users.save(u);
        return buildResponse(u);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        User u = users.findByEmail(req.email())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        if (u.getPasswordHash() == null || !encoder.matches(req.password(), u.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        if (u.getTotpSecret() != null && !u.getTotpSecret().isBlank()) {
            String otp = req.otp();
            if (otp == null || otp.isBlank()) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, CODE_OTP_REQUIRED,
                        "Two-factor authentication code required");
            }
            if (!totp.verify(u.getTotpSecret(), otp.trim())) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, CODE_OTP_INVALID,
                        "Invalid two-factor authentication code");
            }
        }
        return buildResponse(u);
    }

    /**
     * Generates a fresh TOTP secret + Authenticator-scannable otpauth URI but
     * does NOT persist anything. The client renders the QR, asks the user to
     * scan + enter the first code, then calls {@link #enableTwoFactor} which
     * verifies the code against the secret and commits it. This two-step shape
     * stops half-set-up accounts (secret saved but user never scanned).
     */
    @Transactional(readOnly = true)
    public TwoFactorSetupResponse startTwoFactorSetup(Long userId) {
        User u = users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Stale session"));
        String secret = totp.generateSecretBase32();
        String otpauthUri = totp.buildOtpauthUri(OTP_ISSUER, u.getEmail(), secret);
        return new TwoFactorSetupResponse(secret, otpauthUri);
    }

    @Transactional
    public TwoFactorStatusResponse enableTwoFactor(Long userId, TwoFactorEnableRequest req) {
        User u = users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Stale session"));
        if (!totp.verify(req.secret(), req.code().trim())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CODE_OTP_INVALID,
                    "Code did not match the secret. Re-scan and try again.");
        }
        u.setTotpSecret(req.secret());
        users.save(u);
        return new TwoFactorStatusResponse(true);
    }

    @Transactional
    public TwoFactorStatusResponse disableTwoFactor(Long userId) {
        User u = users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Stale session"));
        u.setTotpSecret(null);
        users.save(u);
        return new TwoFactorStatusResponse(false);
    }

    @Transactional(readOnly = true)
    public TwoFactorStatusResponse getTwoFactorStatus(Long userId) {
        User u = users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Stale session"));
        return new TwoFactorStatusResponse(u.getTotpSecret() != null && !u.getTotpSecret().isBlank());
    }

    /**
     * OAuth login.
     *
     * <ul>
     *   <li><b>google</b>: real flow. The frontend obtains an ID token from
     *       Google Identity Services and passes it in {@code idToken}; we hand it
     *       to {@link GoogleTokenVerifier} which validates signature, issuer, and
     *       audience via Google's tokeninfo endpoint. The verified {@code sub}
     *       claim becomes our stable {@code oauth_subject} (so the same Google
     *       account always maps to the same local user row, even if the email
     *       changes).</li>
     *   <li><b>apple</b>: real flow when {@code app.oauth.apple.client-id} is
     *       configured. The frontend obtains an {@code id_token} via Apple's JS
     *       SDK and passes it in {@code idToken}; {@link AppleTokenVerifier}
     *       verifies the JWS signature against Apple's JWKS and the issuer /
     *       audience. The verified {@code sub} becomes our stable
     *       {@code oauth_subject}. When the Services ID isn't configured we fall
     *       back to a deterministic demo user (offline dev / CI / live demo).</li>
     * </ul>
     */
    @Transactional
    public AuthResponse oauthLogin(String provider, String idToken) {
        String key = provider == null ? "" : provider.toLowerCase();
        return switch (key) {
            case "google" -> googleOauthLogin(idToken);
            case "apple" -> appleOauthLogin(idToken);
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported OAuth provider: " + provider);
        };
    }

    private AuthResponse googleOauthLogin(String idToken) {
        GoogleTokenVerifier.VerifiedClaims claims = googleVerifier.verify(idToken);
        // Look up by (provider, subject) first — that's the only identifier guaranteed
        // stable across email changes. Fall back to email match so a user who first
        // registered with email/password and now signs in with Google attaches to
        // the same row rather than getting a duplicate.
        User u = users.findByOauthProviderAndOauthSubject("google", claims.subject())
                .or(() -> users.findByEmail(claims.email()))
                .orElseGet(User::new);

        boolean isNew = u.getId() == null;
        u.setEmail(claims.email());
        u.setOauthProvider("google");
        u.setOauthSubject(claims.subject());
        if (u.getDisplayName() == null || u.getDisplayName().isBlank()) {
            u.setDisplayName(claims.displayName() != null && !claims.displayName().isBlank()
                    ? claims.displayName()
                    : claims.email());
        }
        if (isNew) {
            u.setRole("USER");
        }
        users.save(u);
        return buildResponse(u);
    }

    private AuthResponse appleOauthLogin(String idToken) {
        List<String> audiences = appleAudiences();
        if (audiences.isEmpty()) {
            // Services ID not configured — keep the login screen functional with a
            // deterministic demo user, mirroring the Google offline-dev fallback.
            return mockOauthLogin("apple");
        }

        AppleTokenVerifier.VerifiedClaims claims = appleVerifier.verify(idToken, audiences);
        String email = claims.email();
        // Match by (provider, subject) first — the only identifier guaranteed stable
        // across email changes / private-relay rotation. Fall back to email so an
        // account that first registered with email/password attaches to the same row.
        User u = users.findByOauthProviderAndOauthSubject("apple", claims.subject())
                .or(() -> email == null || email.isBlank() ? Optional.<User>empty() : users.findByEmail(email))
                .orElseGet(User::new);

        boolean isNew = u.getId() == null;
        if (email != null && !email.isBlank()) {
            u.setEmail(email);
        } else if (u.getEmail() == null) {
            // Apple only returns the email when the user grants the scope; synthesize a
            // stable placeholder so the NOT NULL email column is satisfied for new users.
            u.setEmail(claims.subject() + "@privaterelay.appleid.com");
        }
        u.setOauthProvider("apple");
        u.setOauthSubject(claims.subject());
        if (u.getDisplayName() == null || u.getDisplayName().isBlank()) {
            // Apple doesn't put the name in the ID token (only in the first
            // authorization's form post), so fall back to the email.
            u.setDisplayName(u.getEmail());
        }
        if (isNew) {
            u.setRole("USER");
        }
        users.save(u);
        return buildResponse(u);
    }

    /** Configured Apple Services ID(s) — comma-separated → allowed {@code aud} values. */
    private List<String> appleAudiences() {
        if (props.oauth() == null || props.oauth().apple() == null) {
            return List.of();
        }
        String raw = props.oauth().apple().clientId();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private AuthResponse mockOauthLogin(String key) {
        String email = key + ".demo@arxivlens.local";
        String subject = "demo-" + key;
        String displayName = Character.toUpperCase(key.charAt(0)) + key.substring(1) + " Demo";
        User u = users.findByEmail(email).orElseGet(() -> {
            User n = new User();
            n.setEmail(email);
            n.setDisplayName(displayName);
            n.setOauthProvider(key);
            n.setOauthSubject(subject);
            n.setRole("USER");
            return users.save(n);
        });
        return buildResponse(u);
    }

    private AuthResponse buildResponse(User u) {
        String token = jwt.issue(u);
        return new AuthResponse(
                token,
                jwt.expirationSeconds(),
                new UserSummary(u.getId(), u.getEmail(), u.getDisplayName(), u.getRole())
        );
    }
}
