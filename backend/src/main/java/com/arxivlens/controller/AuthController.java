package com.arxivlens.controller;

import com.arxivlens.dto.AuthDtos.AuthResponse;
import com.arxivlens.dto.AuthDtos.ForgotPasswordRequest;
import com.arxivlens.dto.AuthDtos.LoginRequest;
import com.arxivlens.dto.AuthDtos.OAuthLoginRequest;
import com.arxivlens.dto.AuthDtos.RegisterRequest;
import com.arxivlens.dto.AuthDtos.ResetPasswordRequest;
import com.arxivlens.service.AuthService;
import com.arxivlens.service.PasswordResetService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final PasswordResetService passwordReset;

    public AuthController(AuthService auth, PasswordResetService passwordReset) {
        this.auth = auth;
        this.passwordReset = passwordReset;
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req);
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        AuthResponse body = auth.register(req);
        return ResponseEntity.status(201).body(body);
    }

    /**
     * OAuth login. For {@code provider=google} the body must include
     * {@code idToken} (Google Identity Services hands it to the frontend). For
     * {@code provider=apple} the body is ignored — Apple Sign In remains a mock.
     *
     * <p>The body is optional so that the legacy mock call shape (no body) still
     * works for {@code apple} and during local dev where Google isn't wired.
     */
    @PostMapping("/oauth/{provider}")
    public AuthResponse oauthLogin(@PathVariable String provider,
                                   @RequestBody(required = false) OAuthLoginRequest body) {
        String idToken = body == null ? null : body.idToken();
        return auth.oauthLogin(provider, idToken);
    }

    /**
     * Always returns 204 — the response intentionally doesn't reveal whether the
     * email is registered (anti-enumeration). Real outcome is conveyed via email.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        passwordReset.requestReset(req);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        passwordReset.resetPassword(req);
        return ResponseEntity.noContent().build();
    }
}
