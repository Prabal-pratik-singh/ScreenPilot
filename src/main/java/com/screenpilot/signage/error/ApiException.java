package com.screenpilot.signage.error;

import org.springframework.http.HttpStatus;

/**
 * The one exception type business code throws for expected failures ("playlist not
 * found", "email already used"). It carries the HTTP status to return, and the
 * GlobalExceptionHandler turns it into a clean JSON error response. Being a
 * RuntimeException (unchecked), it can be thrown from anywhere without cluttering
 * method signatures. The static factories keep call sites short and readable.
 */
public class ApiException extends RuntimeException {

    // The HTTP status this error should produce (404, 400, ...); final because an
    // exception's meaning never changes after it is created.
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        // super(message) stores the text in RuntimeException, so getMessage() can
        // return it later — it becomes the "message" field of the JSON error body.
        super(message);
        this.status = status;
    }

    /** Read by GlobalExceptionHandler to set the HTTP response code. */
    public HttpStatus getStatus() {
        return status;
    }

    // ---- Static factory methods ----
    // Each one pairs a common failure with its HTTP status, so business code reads
    // like English: `throw ApiException.notFound("Playlist not found")` instead of
    // spelling out `new ApiException(HttpStatus.NOT_FOUND, ...)` at every call site.

    /** 404 — the requested thing does not exist (or is soft-deleted). */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    /** 400 — the client sent something invalid (bad input, unsupported file, ...). */
    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    /** 403 — the caller is authenticated but not allowed to do this. */
    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    /** 401 — the caller is not authenticated at all (missing/invalid credentials). */
    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message);
    }

    /** 409 — a valid request that clashes with current state (e.g. duplicate email). */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }
}
