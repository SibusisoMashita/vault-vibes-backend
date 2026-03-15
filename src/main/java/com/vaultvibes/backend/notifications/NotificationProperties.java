package com.vaultvibes.backend.notifications;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds to the {@code notifications} block in application.yml:
 *
 * <pre>
 * notifications:
 *   eventbridge:
 *     bus-name: vault-vibes-events
 *     source: vaultvibes.finance
 *   whatsapp:
 *     phone-id: ...
 *     token: ...
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "notifications")
@Getter
@Setter
public class NotificationProperties {

    private EventBridgeProps eventbridge = new EventBridgeProps();
    private WhatsAppProps whatsapp = new WhatsAppProps();

    @Getter
    @Setter
    public static class EventBridgeProps {
        private String busName;
        private String source;
    }

    @Getter
    @Setter
    public static class WhatsAppProps {
        private String phoneId;
        private String token;
    }
}

