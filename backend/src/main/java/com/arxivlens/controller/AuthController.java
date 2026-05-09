package com.arxivlens.controller;

import com.arxivlens.dto.AuthDtos.AuthResponse;
import com.arxivlens.dto.AuthDtos.LoginRequest;
import com.arxivlens.dto.AuthDtos.RegisterRequest;
import com.arxivlens.service.AuthService;
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

    public AuthController(AuthService auth) {
        this.auth = auth;
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
     * Mock OAuth — accepts {@code google} or {@code apple} only. See AuthService
     * docstring for why this is a stub instead of a real OAuth handshake.
     */
    @PostMapping("/oauth/{provider}")
    public AuthResponse oauthLogin(@PathVariable String provider) {
        return auth.oauthLogin(provider);
    }
}
