package com.arxivlens.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {}

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password,
            /**
             * Optional 6-digit TOTP for accounts with 2FA enabled. Empty / absent
             * on the first call; backend responds {@code 401 code=OTP_REQUIRED}
             * and the client retries with the code filled in.
             */
            String otp
    ) {}

    public record TwoFactorSetupResponse(
            String secret,
            String otpauthUri
    ) {}

    public record TwoFactorEnableRequest(
            @NotBlank @Size(min = 16, max = 64) String secret,
            @NotBlank @Size(min = 6, max = 6) String code
    ) {}

    public record TwoFactorStatusResponse(boolean enabled) {}

    public record RegisterRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 128) String password,
            @Size(max = 128) String displayName
    ) {}

    public record AuthResponse(
            String token,
            long expiresIn,
            UserSummary user
    ) {}

    public record UserSummary(
            Long id,
            String email,
            String displayName,
            String role
    ) {}

    public record ForgotPasswordRequest(
            @NotBlank @Email String email
    ) {}

    public record ResetPasswordRequest(
            @NotBlank String token,
            @NotBlank @Size(min = 8, max = 128) String password
    ) {}

    /**
     * Body for {@code POST /api/auth/oauth/{provider}}. {@code idToken} is the
     * identity token the frontend obtained from the provider — minted by Google
     * Identity Services for {@code provider=google}, or by Apple's JS SDK for
     * {@code provider=apple}. It may be omitted only when the provider isn't
     * configured server-side and the backend falls back to a mock demo user.
     */
    public record OAuthLoginRequest(String idToken) {}
}
