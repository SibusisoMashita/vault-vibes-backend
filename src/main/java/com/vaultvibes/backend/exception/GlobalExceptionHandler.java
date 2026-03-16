package com.vaultvibes.backend.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Centralized error handling for all API endpoints.
 *
 * Every error response includes:
 *   - error:      machine-readable error code
 *   - message:    human-readable description (when applicable)
 *   - timestamp:  ISO-8601 timestamp
 *   - requestId:  unique correlation ID for log tracing
 */
@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(UserNotRegisteredException.class)
    public ResponseEntity<Map<String, String>> handleNotRegistered(UserNotRegisteredException ex) {
        String requestId = generateRequestId();
        log.warn("AUTH_NOT_REGISTERED: valid JWT but no matching DB user [requestId={}]", requestId);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(errorBody("USER_NOT_REGISTERED", "Valid token but user is not registered in the system.", requestId));
    }

    @ExceptionHandler(UserNotActiveException.class)
    public ResponseEntity<Map<String, String>> handleNotActive(UserNotActiveException ex) {
        String requestId = generateRequestId();
        log.warn("AUTH_NOT_ACTIVE: reason={} [requestId={}]", ex.getReason(), requestId);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(errorBody("USER_NOT_ACTIVE", ex.getReason(), requestId));
    }

    @ExceptionHandler(UserForbiddenException.class)
    public ResponseEntity<Map<String, String>> handleForbidden(UserForbiddenException ex) {
        String requestId = generateRequestId();
        log.warn("FORBIDDEN: {} [requestId={}]", ex.getMessage(), requestId);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(errorBody("FORBIDDEN", ex.getMessage(), requestId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        String requestId = generateRequestId();
        log.warn("BAD_REQUEST: {} [requestId={}]", ex.getMessage(), requestId);
        return ResponseEntity.badRequest()
                .body(errorBody("BAD_REQUEST", ex.getMessage(), requestId));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        String requestId = generateRequestId();
        log.warn("CONFLICT: {} [requestId={}]", ex.getMessage(), requestId);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(errorBody("CONFLICT", ex.getMessage(), requestId));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        String requestId = generateRequestId();
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("VALIDATION_ERROR: {} [requestId={}]", errors, requestId);
        return ResponseEntity.badRequest()
                .body(errorBody("VALIDATION_ERROR", errors, requestId));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNoResource(NoResourceFoundException ex) {
        log.debug("Static resource not found: {}", ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(errorBody("NOT_FOUND", "Resource not found", generateRequestId()));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> handleIo(IOException ex) {
        String requestId = generateRequestId();
        log.warn("IO_ERROR: {} [requestId={}]", ex.getMessage(), requestId);
        return ResponseEntity.badRequest()
                .body(errorBody("IO_ERROR", "Could not read uploaded file.", requestId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneral(Exception ex) {
        String requestId = generateRequestId();
        log.error("INTERNAL_ERROR [requestId={}]", requestId, ex);
        return ResponseEntity.internalServerError()
                .body(errorBody("INTERNAL_ERROR", "An unexpected error occurred.", requestId));
    }

    private static Map<String, String> errorBody(String error, String message, String requestId) {
        return Map.of(
                "error", error,
                "message", message,
                "timestamp", OffsetDateTime.now().toString(),
                "requestId", requestId
        );
    }

    private static String generateRequestId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
