package com.vaultvibes.backend.notifications;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Immutable payload that is serialised into the EventBridge event {@code detail} field.
 *
 * @param userId       the member's UUID (for tracing / audit)
 * @param phoneNumber  E.164 phone number that Lambda will send the WhatsApp message to
 * @param amount       monetary amount relevant to the event (may be null for non-monetary events)
 */
public record NotificationEventDetail(
        UUID userId,
        String phoneNumber,
        BigDecimal amount
) {}

