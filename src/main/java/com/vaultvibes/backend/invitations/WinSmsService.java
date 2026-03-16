package com.vaultvibes.backend.invitations;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Sends SMS invitations via the WinSMS REST API.
 *
 * Docs: https://www.winsms.co.za/api/rest/v1/sms/outgoing/send
 *
 * Authentication uses the AUTHORIZATION header with the API key directly.
 * Cognito is configured with MessageAction=SUPPRESS so it never sends its own SMS.
 */
@Service
@Slf4j
public class WinSmsService {

    private static final String WINSMS_URL =
            "https://api.winsms.co.za/api/rest/v1/sms/outgoing/send";

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${winsms.api-key}")
    private String apiKey;

    /**
     * Sends the stokvel invitation SMS to the given phone number.
     *
     * @param phoneNumber E.164 format (+27...)
     * @param username    Cognito username (e.g. "JohnSmith")
     * @param password    Temporary password to include in the message
     */
    public void sendInvite(String phoneNumber, String username, String password) {
        String message = String.format(
                "Welcome to Vault Vibes.%n%n" +
                "Username: %s%n" +
                "Temporary Password: %s%n%n" +
                "Please login and change your password immediately.",
                username, password);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("AUTHORIZATION", apiKey);

        Map<String, Object> body = Map.of(
                "message", message,
                "recipients", List.of(Map.of("mobileNumber", phoneNumber)));

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            restTemplate.postForObject(WINSMS_URL, request, String.class);
            log.info("WinSMS invite sent to phone={}", phoneNumber);
        } catch (Exception e) {
            log.error("WinSMS delivery failed for phone={}: {}", phoneNumber, e.getMessage());
            throw new IllegalStateException("Failed to send SMS invite to " + phoneNumber, e);
        }
    }
}
