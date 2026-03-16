package com.vaultvibes.backend.users;

import com.vaultvibes.backend.auth.CurrentUserService;
import com.vaultvibes.backend.auth.UserContextHolder;
import com.vaultvibes.backend.contributions.ContributionRepository;
import com.vaultvibes.backend.exception.UserNotActiveException;
import com.vaultvibes.backend.exception.UserNotRegisteredException;
import com.vaultvibes.backend.invitations.InvitationRepository;
import com.vaultvibes.backend.notifications.NotificationEventDetail;
import com.vaultvibes.backend.notifications.NotificationEventService;
import com.vaultvibes.backend.notifications.NotificationEventType;
import com.vaultvibes.backend.shares.ShareRepository;
import com.vaultvibes.backend.users.dto.MemberDTO;
import com.vaultvibes.backend.users.dto.UserDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository                userRepository;
    private final ShareRepository               shareRepository;
    private final ContributionRepository        contributionRepository;
    private final CurrentUserService            currentUserService;
    private final InvitationRepository          invitationRepository;
    private final NotificationEventService      notificationEventService;
    private final CognitoIdentityProviderClient cognitoClient;

    @Value("${cognito.user-pool-id}")
    private String userPoolId;

    /**
     * Resolves the authenticated user from the Cognito JWT.
     *
     * Flow:
     *  1. Return cached user if already resolved this request (UserContextInterceptor).
     *  2. Look up by sub (cognito_id) — fast path once the user is linked.
     *  3. Fallback: use Cognito username from the token to call adminGetUser and retrieve
     *     the phone_number, then look up the DB user by phone and link the sub.
     *     This path only runs on first login; afterwards step 2 hits immediately.
     *  4. No match → 401 USER_NOT_REGISTERED.
     *
     * SUSPENDED → 403 USER_NOT_ACTIVE.
     */
    @Transactional
    public UserEntity getCurrentUser() {
        // 1. Return cached user for the current request (populated by UserContextInterceptor)
        UserEntity cached = UserContextHolder.get();
        if (cached != null) {
            return cached;
        }

        String cognitoId       = currentUserService.getCurrentUserId();
        String cognitoUsername = currentUserService.getCurrentUsername();

        // 2. Fast path — look up by sub (always present in access tokens)
        if (cognitoId != null) {
            var byId = userRepository.findByCognitoId(cognitoId);
            if (byId.isPresent()) {
                UserEntity user = byId.get();
                if ("SUSPENDED".equals(user.getStatus())) {
                    throw new UserNotActiveException("SUSPENDED");
                }
                if (!"ACTIVE".equals(user.getStatus())) {
                    user.setStatus("ACTIVE");
                    return userRepository.save(user);
                }
                return user;
            }
        }

        // 3. Sub not linked yet — use Cognito username to find the user's phone number,
        //    then look up in the DB and link the sub. Runs once per user.
        if (cognitoUsername != null) {
            String phoneNumber = resolvePhoneFromCognito(cognitoUsername);
            if (phoneNumber != null) {
                UserEntity locked = userRepository.findWithLockByPhoneNumber(phoneNumber).orElse(null);
                if (locked != null) {
                    if ("SUSPENDED".equals(locked.getStatus())) {
                        throw new UserNotActiveException("SUSPENDED");
                    }
                    return linkCognitoAccount(locked, cognitoId);
                }
            }
        }

        // 4. No matching user — valid JWT but not registered in this application
        log.warn("AUTH_NOT_REGISTERED: cognito_id={} username={}", cognitoId, cognitoUsername);
        throw new UserNotRegisteredException();
    }

    private String resolvePhoneFromCognito(String cognitoUsername) {
        try {
            return cognitoClient.adminGetUser(r -> r
                            .userPoolId(userPoolId)
                            .username(cognitoUsername))
                    .userAttributes().stream()
                    .filter(a -> "phone_number".equals(a.name()))
                    .findFirst()
                    .map(a -> a.value())
                    .orElse(null);
        } catch (CognitoIdentityProviderException e) {
            log.warn("AUTH_COGNITO_LOOKUP_FAILED: username={}: {}", cognitoUsername, e.getMessage());
            return null;
        }
    }

    /**
     * Updates cognito_id on the user record and activates the account if PENDING.
     * Idempotent — safe to call on every login, not just the first.
     */
    @Transactional
    public UserEntity linkCognitoAccount(UserEntity user, String cognitoId) {
        // Already in sync — nothing to do
        if (cognitoId != null && cognitoId.equals(user.getCognitoId()) && "ACTIVE".equals(user.getStatus())) {
            return user;
        }
        if (cognitoId != null) {
            user.setCognitoId(cognitoId);
        }
        user.setStatus("ACTIVE");
        UserEntity saved = userRepository.save(user);

        // Mark the outstanding invitation as ACCEPTED
        invitationRepository.findFirstByUserAndStatusIn(saved, List.of("PENDING", "SENT"))
                .ifPresent(inv -> {
                    inv.setStatus("ACCEPTED");
                    invitationRepository.save(inv);
                    log.info("Invitation id={} accepted for user id={}", inv.getId(), saved.getId());
                });

        log.info("First login: linked cognito_id={} to user id={} phone={}",
                cognitoId, saved.getId(), saved.getPhoneNumber());
        return saved;
    }

    public UserEntity getUserById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
    }

    public UserDTO toDTO(UserEntity user) {
        return new UserDTO(
                user.getId(),
                user.getFullName(),
                user.getPhoneNumber(),
                user.getEmail(),
                user.getStatus(),
                user.getRole(),
                user.getCreatedAt()
        );
    }

    public MemberDTO toMemberDTO(UserEntity user) {
        BigDecimal sharesOwned     = shareRepository.sumShareUnitsByUserId(user.getId());
        BigDecimal totalCommitment = shareRepository.sumCommitmentByUserId(user.getId());
        BigDecimal paidSoFar       = contributionRepository.sumAmountByUserId(user.getId());
        BigDecimal remaining       = totalCommitment.subtract(paidSoFar).max(BigDecimal.ZERO);

        return new MemberDTO(
                user.getId(),
                user.getFullName(),
                user.getPhoneNumber(),
                user.getEmail(),
                user.getRole().toLowerCase(),
                user.getStatus(),
                sharesOwned,
                totalCommitment,
                paidSoFar,
                remaining,
                user.isOnboardingCompleted(),
                user.getOnboardingVersion()
        );
    }

    public List<MemberDTO> listMembers() {
        return userRepository.findAll().stream()
                .map(this::toMemberDTO)
                .toList();
    }

    @Transactional
    public UserDTO updateProfile(UUID id, String fullName, String email) {
        log.info("Updating profile for user {}", id);
        UserEntity user = getUserById(id);
        if (fullName != null) user.setFullName(fullName);
        if (email != null) user.setEmail(email);
        return toDTO(userRepository.save(user));
    }

    @Transactional
    public UserDTO updateStatus(UUID id, String status) {
        log.info("Updating status to {} for user {}", status, id);
        UserEntity user = getUserById(id);
        user.setStatus(status);
        UserDTO saved = toDTO(userRepository.save(user));

        // Immediately invalidate all active Cognito sessions when suspending a user
        if ("SUSPENDED".equals(status) && user.getCognitoId() != null) {
            revokeAllCognitoSessions(user.getCognitoId(), user.getId());
        }

        return saved;
    }

    private void revokeAllCognitoSessions(String cognitoId, UUID userId) {
        try {
            cognitoClient.adminUserGlobalSignOut(r -> r
                    .userPoolId(userPoolId)
                    .username(cognitoId));
            log.info("AUTH_SESSION_REVOKED: cognito_id={} user_id={}", cognitoId, userId);
        } catch (CognitoIdentityProviderException e) {
            // Log but don't fail — the DB status change is the source of truth
            log.warn("AUTH_SESSION_REVOKE_FAILED: cognito_id={} user_id={}: {}",
                    cognitoId, userId, e.getMessage());
        }
    }

    @Transactional
    public MemberDTO completeOnboarding(UserEntity user, int version) {
        user.setOnboardingCompleted(true);
        user.setOnboardingCompletedAt(OffsetDateTime.now());
        user.setOnboardingVersion(version);
        userRepository.save(user);
        log.info("ONBOARDING_COMPLETE: user_id={} version={}", user.getId(), version);
        return toMemberDTO(user);
    }

    @Transactional
    public UserDTO updateRole(UUID id, String role) {
        log.info("Updating role to {} for user {}", role, id);
        UserEntity user = getUserById(id);
        user.setRole(role);
        UserDTO saved = toDTO(userRepository.save(user));

        notificationEventService.publish(
                NotificationEventType.ROLE_UPDATED,
                new NotificationEventDetail(user.getId(), user.getPhoneNumber(), null));

        return saved;
    }

}
