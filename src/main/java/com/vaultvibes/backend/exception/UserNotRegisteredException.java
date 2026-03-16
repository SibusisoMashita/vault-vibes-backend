package com.vaultvibes.backend.exception;

/**
 * Thrown when a valid Cognito JWT is presented but no matching record exists
 * in the application database.
 *
 * Causes: database reset, user deleted, invite cancelled, onboarding incomplete.
 * Maps to HTTP 401 (not 403 — the system cannot confirm identity at all).
 */
public class UserNotRegisteredException extends RuntimeException {

    public UserNotRegisteredException() {
        super("USER_NOT_REGISTERED");
    }
}
