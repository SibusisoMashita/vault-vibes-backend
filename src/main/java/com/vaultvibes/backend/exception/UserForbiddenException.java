package com.vaultvibes.backend.exception;

/**
 * Thrown when a valid JWT exists but the user is not permitted to access the system.
 * Reasons: not invited, suspended, or pending activation.
 * Maps to HTTP 403 via GlobalExceptionHandler.
 */
public class UserForbiddenException extends RuntimeException {

    public UserForbiddenException(String message) {
        super(message);
    }
}
