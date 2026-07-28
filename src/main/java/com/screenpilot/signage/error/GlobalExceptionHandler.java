package com.screenpilot.signage.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central error-to-JSON translator. {@code @RestControllerAdvice} makes Spring route any
 * exception thrown by any controller through the matching {@code @ExceptionHandler}
 * method here, so every error leaves the API in the same JSON shape (timestamp, status,
 * message, path) and no controller needs its own try/catch. Unexpected exceptions are
 * logged in full but returned as a generic 500 so internals never leak to clients.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Used by the catch-all below to record full stack traces server-side.
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Our own ApiException already carries both its status and a message written to
     * be user-safe, so this handler simply passes them straight through.
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex, HttpServletRequest req) {
        return build(ex.getStatus(), ex.getMessage(), req, null);
    }

    /** Thrown when @Valid fails on a request body: returns 400 with a field-by-field error map. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        // Collect one entry per invalid field: its name -> the rule's message (e.g.
        // "name" -> "must not be blank"), so the UI can highlight the exact inputs.
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fields.put(fe.getField(), fe.getDefaultMessage());
        }
        return build(HttpStatus.BAD_REQUEST, "Validation failed", req, fields);
    }

    /** Spring Security throws this on a failed login (wrong email/password) — 401. */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(BadCredentialsException ex, HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), req, null);
    }

    /**
     * Thrown when a @PreAuthorize role check fails: the caller is logged in but
     * lacks the role — 403. The exception's own text ("Access Denied") is
     * developer-speak, so a friendlier fixed sentence is sent instead.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleDenied(AccessDeniedException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "You do not have permission to perform this action", req, null);
    }

    /**
     * An upload bigger than the multipart limit is rejected by Spring before our
     * controller code ever runs, so it needs its own handler here to keep the
     * response in the standard JSON shape — 413 Payload Too Large.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest req) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds the maximum allowed size", req, null);
    }

    /** Catch-all: log the real cause server-side, hand the client a vague 500. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception ex, HttpServletRequest req) {
        // Full detail (method, URL, stack trace) goes to the server log for debugging...
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        // ...but the client only ever sees this generic sentence. Real exception text
        // could leak internals (SQL fragments, file paths, library names) to attackers.
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong. Please try again.", req, null);
    }

    /**
     * Assembles the uniform error body every handler above returns:
     * { timestamp, status, error, message, path, fieldErrors? }.
     * LinkedHashMap preserves this insertion order in the JSON, keeping it readable.
     */
    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message,
                                                      HttpServletRequest req, Map<String, String> fieldErrors) {
        Map<String, Object> body = new LinkedHashMap<>();
        // When it happened, the numeric code (e.g. 404), that code's label
        // ("Not Found"), the human-readable message, and which URL failed.
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("path", req.getRequestURI());
        // Only validation failures carry per-field details; the key is skipped
        // otherwise so simple errors stay compact.
        if (fieldErrors != null && !fieldErrors.isEmpty()) {
            body.put("fieldErrors", fieldErrors);
        }
        // The map becomes the JSON body; the same status is set on the response line.
        return ResponseEntity.status(status).body(body);
    }
}
