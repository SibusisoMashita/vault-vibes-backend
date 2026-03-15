package com.vaultvibes.backend.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;

/**
 * Application-level service that publishes notification events to EventBridge.
 *
 * <p>Callers (LoanService, ContributionService, DistributionService) only need to
 * supply a {@link NotificationEventType} and a {@link NotificationEventDetail}.
 * This service is the single place where EventBridge interaction lives.</p>
 *
 * <p>If EventBridge is unreachable the failure is logged but does NOT propagate —
 * notification failures must not roll back business transactions.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationEventService {

    private final NotificationProperties props;
    private final ObjectMapper objectMapper;

    /**
     * Publishes a notification event to EventBridge.
     *
     * @param eventType the business event (e.g. LOAN_APPROVED)
     * @param detail    payload containing userId, phoneNumber and amount
     */
    public void publish(NotificationEventType eventType, NotificationEventDetail detail) {
        String busName = props.getEventbridge().getBusName();
        String source  = props.getEventbridge().getSource();

        if (busName == null || busName.isBlank()) {
            log.warn("EventBridge bus name is not configured — skipping notification for {}", eventType);
            return;
        }

        try {
            String detailJson = objectMapper.writeValueAsString(detail);

            PutEventsRequestEntry entry = PutEventsRequestEntry.builder()
                    .eventBusName(busName)
                    .source(source)
                    .detailType(eventType.name())
                    .detail(detailJson)
                    .build();

            PutEventsRequest request = PutEventsRequest.builder()
                    .entries(entry)
                    .build();

            try (EventBridgeClient client = EventBridgeClient.builder()
                    .region(Region.of("us-east-1"))
                    .build()) {

                PutEventsResponse response = client.putEvents(request);

                if (response.failedEntryCount() > 0) {
                    log.error("EventBridge rejected {} entries for event {} — failures: {}",
                            response.failedEntryCount(), eventType, response.entries());
                } else {
                    log.info("Published {} event to EventBridge bus '{}' for user {}",
                            eventType, busName, detail.userId());
                }
            }

        } catch (Exception ex) {
            // Notification failures are non-fatal — log and continue
            log.error("Failed to publish {} event to EventBridge: {}", eventType, ex.getMessage(), ex);
        }
    }
}

