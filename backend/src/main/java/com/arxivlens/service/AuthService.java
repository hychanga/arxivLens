package com.arxivlens.service;

import com.arxivlens.dto.AuthDtos.AuthResponse;
import com.arxivlens.dto.AuthDtos.LoginRequest;
import com.arxivlens.dto.AuthDtos.RegisterRequest;
import com.arxivlens.dto.AuthDtos.TwoFactorEnableRequest;
import com.arxivlens.dto.AuthDtos.TwoFactorSetupResponse;
import com.arxivlens.dto.AuthDtos.TwoFactorStatusResponse;
import com.arxivlens.dto.AuthDtos.UserSummary;
import com.arxivlens.entity.User;
import com.arxivlens.repository.UserRepository;
import com.arxivlens.security.JwtService;
import com.arxivlens.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final TotpService totp;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt,
                       GoogleTokenVerifier googleVerifier, TotpService totp) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.googleVerifier = googleVerifier;
        this.totp = totp;
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
     *   <li><b>apple</b>: still a mock — a deterministic demo user is upserted
     *       and a JWT issued. Wiring real Apple Sign In requires a paid Apple
     *       Developer account ($99/yr) plus JWS-signed client secrets, which is
     *       out of scope for the hobby tier.</li>
     * </ul>
     */
    @Transactional
    public AuthResponse oauthLogin(String provider, String idToken) {
        String key = provider == null ? "" : provider.toLowerCase();
        return switch (key) {
            case "google" -> googleOauthLogin(idToken);
            case "apple" -> mockOauthLogin("apple");
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
