package com.vaultvibes.backend.invitations;

import com.vaultvibes.backend.auth.CurrentUserService;
import com.vaultvibes.backend.invitations.dto.InvitationDTO;
import com.vaultvibes.backend.notifications.NotificationEventDetail;
import com.vaultvibes.backend.notifications.NotificationEventService;
import com.vaultvibes.backend.notifications.NotificationEventType;
import com.vaultvibes.backend.shares.ShareService;
import com.vaultvibes.backend.users.UserEntity;
import com.vaultvibes.backend.users.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Manages stokvel member onboarding.
 *
 * Flow:
 *   invite   → create PENDING user → create invitation → create Cognito user (SMS suppressed)
 *              → send WinSMS → mark invitation SENT
 *   resend   → reset Cognito temp password → send WinSMS → stamp resent_at → ensure SENT
 *   activate → triggered by UserService.linkCognitoAccount on first login
 *              → user ACTIVE, invitation ACCEPTED
 *
 * Cognito SMS is always suppressed. WinSMS is the sole delivery channel.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class InvitationService {

    private final InvitationRepository          invitationRepository;
    private final UserRepository                userRepository;
    private final CurrentUserService            currentUserService;
    private final NotificationEventService      notificationEventService;
    private final CognitoIdentityProviderClient cognitoClient;
    private final WinSmsService                 winSmsService;
    private final ShareService                  shareService;

    @Value("${cognito.user-pool-id}")
    private String userPoolId;

    public List<InvitationDTO> listAll() {
        return invitationRepository.findAll().stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * Invites a new member or re-invites an existing PENDING user.
     *
     * If a user with this phone already exists and is PENDING, we resend their invite
     * rather than creating a duplicate. If they are ACTIVE, we reject.
     */
    @Transactional
    public InvitationDTO inviteMember(String fullName, String phoneNumber, String role, int shareUnits) {
        UserEntity user = userRepository.findByPhoneNumber(phoneNumber).orElse(null);

        if (user != null) {
            if ("ACTIVE".equals(user.getStatus())) {
                throw new IllegalStateException(
                        "A user with phone number " + phoneNumber + " is already an active member.");
            }
            // PENDING user — resend their active invitation if one exists, or create a
            // fresh invitation if the previous one was deleted. This handles the case where
            // an admin deletes an invitation but the PENDING user record remains in the DB.
            InvitationEntity activeInvite = invitationRepository
                    .findFirstByUserAndStatusIn(user, List.of("PENDING", "SENT"))
                    .orElse(null);
            if (activeInvite != null) {
                return resendInvitation(activeInvite.getId());
            }
            log.info("PENDING user {} has no active invitation (previous was deleted) — creating a new one", phoneNumber);
            UUID invitedBy = resolveInviterId();
            InvitationEntity freshInvite = new InvitationEntity();
            freshInvite.setUser(user);
            freshInvite.setInvitedBy(invitedBy);
            InvitationEntity savedFresh = invitationRepository.save(freshInvite);
            return resendInvitation(savedFresh.getId());
        }

        UUID invitedBy = resolveInviterId();

        // Create the user first — they are PENDING until first login
        UserEntity newUser = new UserEntity();
        newUser.setFullName(fullName);
        newUser.setPhoneNumber(phoneNumber);
        newUser.setRole(role.toUpperCase());
        newUser.setStatus("PENDING");
        UserEntity savedUser = userRepository.save(newUser);
        log.info("Created PENDING user id={} phone={}", savedUser.getId(), phoneNumber);

        // Allocate shares immediately — committed even if the user hasn't logged in yet
        shareService.allocateShares(savedUser, shareUnits);
        log.info("Allocated {} shares for user id={}", shareUnits, savedUser.getId());

        // Create invitation linked to the user
        InvitationEntity invitation = new InvitationEntity();
        invitation.setUser(savedUser);
        invitation.setInvitedBy(invitedBy);
        InvitationEntity savedInvite = invitationRepository.save(invitation);

        String tempPassword = generateTemporaryPassword();

        // Only create a Cognito user if one doesn't already exist for this phone.
        // Creating a duplicate would give the user two Cognito accounts with different
        // subs, causing identity-conflict errors on every subsequent login.
        String existingUsername = findCognitoUsernameByPhoneNumber(phoneNumber);
        String cognitoUsername;
        if (existingUsername != null) {
            cognitoUsername = existingUsername;
            resetCognitoPassword(cognitoUsername, tempPassword);
            log.info("Cognito user already exists for phone={}, reset password for username={}", phoneNumber, cognitoUsername);
        } else {
            cognitoUsername = fullName.replaceAll("\\s+", "");
            createCognitoUser(cognitoUsername, tempPassword, phoneNumber, fullName);
        }

        // Store the Cognito sub in the DB now so first-login can find the user by sub
        // directly rather than falling back to phone number lookup.
        String cognitoSub = findCognitoSubByUsername(cognitoUsername);
        if (cognitoSub != null) {
            savedUser.setCognitoId(cognitoSub);
            userRepository.save(savedUser);
            log.info("Stored cognito_id={} for user id={}", cognitoSub, savedUser.getId());
        }

        winSmsService.sendInvite(phoneNumber, cognitoUsername, tempPassword);

        savedInvite.setStatus("SENT");
        savedInvite = invitationRepository.save(savedInvite);

        notificationEventService.publish(
                NotificationEventType.MEMBER_INVITED,
                new NotificationEventDetail(savedUser.getId(), phoneNumber, null));

        log.info("Invitation created id={} user={} phone={}", savedInvite.getId(), savedUser.getId(), phoneNumber);
        return toDTO(savedInvite);
    }

    /**
     * Resends an invitation: generates a new temp password, resets it in Cognito,
     * sends a fresh WinSMS, and stamps resent_at.
     */
    @Transactional
    public InvitationDTO resendInvitation(UUID id) {
        InvitationEntity invitation = invitationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invitation not found: " + id));

        String status = invitation.getStatus();
        if (!"PENDING".equals(status) && !"SENT".equals(status)) {
            throw new IllegalStateException("Cannot resend an invitation with status " + status);
        }

        UserEntity user          = invitation.getUser();
        String     phoneNumber   = user.getPhoneNumber();
        String     cognitoUsername = findCognitoUsernameByPhoneNumber(phoneNumber);
        String     tempPassword    = generateTemporaryPassword();

        if (cognitoUsername != null) {
            resetCognitoPassword(cognitoUsername, tempPassword);
        } else {
            // Cognito user doesn't exist yet — create them (e.g. seeded legacy members)
            String derived = user.getFullName().replaceAll("\\s+", "");
            createCognitoUser(derived, tempPassword, phoneNumber, user.getFullName());
            cognitoUsername = derived;
        }

        winSmsService.sendInvite(phoneNumber, cognitoUsername, tempPassword);

        invitation.setResentAt(OffsetDateTime.now());
        invitation.setStatus("SENT");
        InvitationEntity saved = invitationRepository.save(invitation);

        log.info("Invitation resent id={} user={} phone={}", saved.getId(), user.getId(), phoneNumber);
        return toDTO(saved);
    }

    @Transactional
    public void deleteInvitation(UUID id) {
        InvitationEntity invitation = invitationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invitation not found: " + id));
        log.info("Invitation deleted id={} user={}", invitation.getId(), invitation.getUser().getId());
        invitationRepository.delete(invitation);
    }

    // ── Cognito helpers ──────────────────────────────────────────────────────────

    /**
     * Creates a Cognito user with MessageAction=SUPPRESS so no Cognito SMS is sent.
     * WinSMS handles delivery exclusively.
     */
    private void createCognitoUser(String username, String tempPassword,
                                   String phoneNumber, String fullName) {
        try {
            cognitoClient.adminCreateUser(r -> r
                    .userPoolId(userPoolId)
                    .username(username)
                    .temporaryPassword(tempPassword)
                    .messageAction(MessageActionType.SUPPRESS)
                    .userAttributes(
                            AttributeType.builder().name("phone_number").value(phoneNumber).build(),
                            AttributeType.builder().name("phone_number_verified").value("true").build(),
                            AttributeType.builder().name("name").value(fullName).build()));
            log.info("Cognito user created username={} phone={}", username, phoneNumber);
        } catch (CognitoIdentityProviderException e) {
            log.error("Cognito user creation failed username={}: {}", username, e.getMessage());
            throw new IllegalStateException("Failed to create Cognito user: " + e.getMessage(), e);
        }
    }

    /**
     * Resets the Cognito temporary password (non-permanent) so the user is forced
     * to change it on next login.
     */
    private void resetCognitoPassword(String cognitoUsername, String newPassword) {
        try {
            cognitoClient.adminSetUserPassword(r -> r
                    .userPoolId(userPoolId)
                    .username(cognitoUsername)
                    .password(newPassword)
                    .permanent(false));
            log.info("Cognito password reset for username={}", cognitoUsername);
        } catch (CognitoIdentityProviderException e) {
            log.error("Cognito password reset failed username={}: {}", cognitoUsername, e.getMessage());
            throw new IllegalStateException("Failed to reset Cognito password: " + e.getMessage(), e);
        }
    }

    private String findCognitoUsernameByPhoneNumber(String phoneNumber) {
        return cognitoClient.listUsers(r -> r
                        .userPoolId(userPoolId)
                        .filter("phone_number = \"" + phoneNumber + "\"")
                        .limit(1))
                .users().stream()
                .findFirst()
                .map(u -> u.username())
                .orElse(null);
    }

    /**
     * Retrieves the Cognito sub (UUID) for a user by their username.
     * The sub is the stable identity key stored as cognito_id in the DB.
     */
    private String findCognitoSubByUsername(String username) {
        try {
            return cognitoClient.adminGetUser(r -> r
                            .userPoolId(userPoolId)
                            .username(username))
                    .userAttributes().stream()
                    .filter(a -> "sub".equals(a.name()))
                    .findFirst()
                    .map(a -> a.value())
                    .orElse(null);
        } catch (CognitoIdentityProviderException e) {
            log.warn("Could not retrieve sub for username={}: {}", username, e.getMessage());
            return null;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /**
     * Generates a 12-character password that satisfies Cognito's default policy:
     * at least one uppercase, lowercase, digit, and special character.
     */
    private String generateTemporaryPassword() {
        String upper   = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String lower   = "abcdefghijklmnopqrstuvwxyz";
        String digits  = "0123456789";
        String special = "!@#$%^&*";
        String all     = upper + lower + digits + special;

        SecureRandom rng  = new SecureRandom();
        List<Character> chars = new ArrayList<>();
        // Guarantee at least one of each required class
        chars.add(upper.charAt(rng.nextInt(upper.length())));
        chars.add(lower.charAt(rng.nextInt(lower.length())));
        chars.add(digits.charAt(rng.nextInt(digits.length())));
        chars.add(special.charAt(rng.nextInt(special.length())));
        for (int i = 4; i < 12; i++) {
            chars.add(all.charAt(rng.nextInt(all.length())));
        }
        Collections.shuffle(chars, rng);

        StringBuilder sb = new StringBuilder(chars.size());
        chars.forEach(sb::append);
        return sb.toString();
    }

    private UUID resolveInviterId() {
        String cognitoId = currentUserService.getCurrentUserId();
        if (cognitoId == null) return null;
        return userRepository.findByCognitoId(cognitoId)
                .map(UserEntity::getId)
                .orElse(null);
    }

    private InvitationDTO toDTO(InvitationEntity e) {
        UserEntity u = e.getUser();
        return new InvitationDTO(
                e.getId(),
                u.getId(),
                u.getFullName(),
                u.getPhoneNumber(),
                u.getRole(),
                e.getInvitedBy(),
                e.getStatus(),
                e.getResentAt(),
                e.getCreatedAt()
        );
    }
}
