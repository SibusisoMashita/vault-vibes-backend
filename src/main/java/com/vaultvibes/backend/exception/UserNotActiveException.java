package com.vaultvibes.backend.exception;

/**
 * Thrown when the DB user exists but is not in ACTIVE status.
 * Maps to HTTP 403 — identity is confirmed but access is denied.
 *
 * Status reasons: PENDING (onboarding incomplete) or SUSPENDED (admin action).
 */
public class UserNotActiveException extends RuntimeException {

    private final String reason;

    public UserNotActiveException(String reason) {
        super("USER_NOT_ACTIVE");
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
