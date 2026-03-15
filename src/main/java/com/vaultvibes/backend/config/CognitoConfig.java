package com.vaultvibes.backend.config;

/**
 * Cognito SDK client configuration.
 *
 * The backend no longer calls Cognito APIs directly — Cognito manages user
 * authentication and invitations autonomously. Account linking (sub → DB user)
 * happens via JWT claims in UserService.getCurrentUser().
 *
 * This class is intentionally not annotated with @Configuration.
 * Remove this file and the AWS Cognito SDK dependency once the project is cleaned up.
 */
public class CognitoConfig {
}
