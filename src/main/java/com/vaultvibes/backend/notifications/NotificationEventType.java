package com.vaultvibes.backend.notifications;

/**
 * All event type identifiers that the application may publish to EventBridge.
 *
 * These are used as the {@code detail-type} field in EventBridge events and
 * must match the cases handled by the Lambda notification handler.
 */
public enum NotificationEventType {
    LOAN_APPROVED,
    LOAN_ISSUED,
    CONTRIBUTION_OVERDUE,
    DISTRIBUTION_EXECUTED,
    MEMBER_INVITED,
    ROLE_UPDATED
}

