package com.arxivlens.service;

import com.arxivlens.dto.AuthDtos.AuthResponse;
import com.arxivlens.dto.AuthDtos.LoginRequest;
import com.arxivlens.dto.AuthDtos.RegisterRequest;
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

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
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
        return buildResponse(u);
    }

    /**
     * Mock OAuth login. Per arxivLens-requirements §10 ("前端模擬"), real OAuth flows
     * are out of scope — instead we upsert a deterministic per-provider demo user and
     * issue a normal JWT. Each provider gets its own row so favorites / downloads /
     * preferences stay isolated between providers.
     */
    @Transactional
    public AuthResponse oauthLogin(String provider) {
        String key = provider == null ? "" : provider.toLowerCase();
        if (!key.equals("google") && !key.equals("apple")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported OAuth provider: " + provider);
        }
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
