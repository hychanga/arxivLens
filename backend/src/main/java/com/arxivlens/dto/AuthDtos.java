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
     * Body for {@code POST /api/auth/oauth/{provider}}.
     * For {@code provider=google}: {@code idToken} is the JWT minted by Google
     * Identity Services on the frontend. For {@code provider=apple} (still a
     * mock in this codebase): the field can be omitted.
     */
    public record OAuthLoginRequest(String idToken) {}
}
