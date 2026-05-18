package com.arxivlens.web;

import org.springframework.http.HttpStatus;

/**
 * Application-level exception carried through to the global handler. The
 * optional {@link #getCode() code} lets the frontend distinguish a generic
 * 401 (bad password) from a domain-specific one (e.g. {@code "OTP_REQUIRED"})
 * without parsing free-form messages.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String message) {
        this(status, null, message);
    }

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** Optional machine-readable error code (null for plain errors). */
    public String getCode() {
        return code;
    }
}
