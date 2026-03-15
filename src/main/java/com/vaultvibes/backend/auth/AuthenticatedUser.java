package com.vaultvibes.backend.auth;

import java.util.List;

/**
 * Internal representation of the authenticated Cognito user extracted from the JWT.
 * Fields are mapped from standard Cognito JWT claims.
 */
public record AuthenticatedUser(
        String id,           // JWT sub claim — Cognito user UUID
        String email,        // email claim
        String phoneNumber,  // phone_number claim
        List<String> roles   // derived from cognito:groups
) {}
