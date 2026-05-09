package com.arxivlens.web;

import com.arxivlens.security.JwtAuthenticationFilter;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class AuthUtil {

    private AuthUtil() {}

    public static Long currentUserId() {
        return principal().id();
    }

    public static String currentUserEmail() {
        return principal().email();
    }

    public static String currentUserRole() {
        return principal().role();
    }

    public static boolean isAdmin() {
        return "ADMIN".equals(currentUserRole());
    }

    private static JwtAuthenticationFilter.AuthenticatedUser principal() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || a.getPrincipal() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        if (a.getPrincipal() instanceof JwtAuthenticationFilter.AuthenticatedUser u) {
            return u;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED,
                "Unexpected principal type: " + a.getPrincipal().getClass());
    }
}
