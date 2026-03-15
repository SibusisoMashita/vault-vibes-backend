package com.vaultvibes.backend.users;

import com.vaultvibes.backend.auth.CurrentUserService;
import com.vaultvibes.backend.contributions.ContributionRepository;
import com.vaultvibes.backend.exception.UserForbiddenException;
import com.vaultvibes.backend.invitations.InvitationEntity;
import com.vaultvibes.backend.invitations.InvitationRepository;
import com.vaultvibes.backend.notifications.NotificationEventDetail;
import com.vaultvibes.backend.notifications.NotificationEventService;
import com.vaultvibes.backend.notifications.NotificationEventType;
import com.vaultvibes.backend.shares.ShareEntity;
import com.vaultvibes.backend.shares.ShareRepository;
import com.vaultvibes.backend.users.dto.MemberDTO;
import com.vaultvibes.backend.users.dto.UserDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository           userRepository;
    private final ShareRepository          shareRepository;
    private final ContributionRepository   contributionRepository;
    private final CurrentUserService       currentUserService;
    private final InvitationRepository     invitationRepository;
    private final NotificationEventService notificationEventService;

    /**
     * Resolves the authenticated user from the Cognito JWT.
     *
     * Flow:
     *  1. Look up by cognito_id (sub) — fast path for returning users.
     *  2. Look up by phone_number — first login: link sub, activate account, allocate shares.
     *  3. No match → 403: this Cognito user was not invited to the stokvel.
     *
     * Only ACTIVE users are allowed to proceed. PENDING and SUSPENDED throw 403.
     */
    @Transactional
    public UserEntity getCurrentUser() {
        String cognitoId   = currentUserService.getCurrentUserId();
        String phoneNumber = currentUserService.getCurrentPhoneNumber();

        // 1. Fast path — look up by stable Cognito sub
        if (cognitoId != null) {
            var byId = userRepository.findByCognitoId(cognitoId);
            if (byId.isPresent()) {
                enforceActive(byId.get());
                return byId.get();
            }
        }

        // 2. First login — find PENDING user by phone_number, link sub and activate
        if (phoneNumber != null && cognitoId != null) {
            var byPhone = userRepository.findByPhoneNumber(phoneNumber);
            if (byPhone.isPresent()) {
                UserEntity user = byPhone.get();
                if ("SUSPENDED".equals(user.getStatus())) {
                    throw new UserForbiddenException("Account is suspended.");
                }
                // Safety: only link if no Cognito sub is set yet (cognito_id IS NULL).
                // If a different sub is already linked, reject — this prevents identity takeover.
                if (user.getCognitoId() != null && !user.getCognitoId().equals(cognitoId)) {
                    log.warn("Identity conflict: user id={} phone={} is already linked to a different Cognito sub",
                            user.getId(), phoneNumber);
                    throw new UserForbiddenException("User not invited to this stokvel.");
                }
                return linkCognitoAccount(user, cognitoId);
            }
        }

        // 3. No matching user — not invited
        log.warn("Rejected access: no DB user for cognito_id={} phone_number={}", cognitoId, phoneNumber);
        throw new UserForbiddenException("User not invited to this stokvel.");
    }

    /**
     * Links a Cognito sub to a pre-created PENDING user, activates their account,
     * and allocates the shares reserved on their invitation.
     *
     * The cognitoId IS NULL check is enforced by the caller, but guarded here too
     * so this method is safe to call independently.
     */
    @Transactional
    public UserEntity linkCognitoAccount(UserEntity user, String cognitoId) {
        // If already linked to this same sub, just activate and return (idempotent)
        if (cognitoId.equals(user.getCognitoId()) && "ACTIVE".equals(user.getStatus())) {
            return user;
        }
        user.setCognitoId(cognitoId);
        user.setStatus("ACTIVE");
        UserEntity saved = userRepository.save(user);

        // Allocate reserved shares from the pending invitation
        invitationRepository.findFirstByPhoneNumberAndStatusIn(
                user.getPhoneNumber(), List.of("PENDING"))
                .ifPresent(inv -> {
                    ShareEntity share = new ShareEntity();
                    share.setUser(saved);
                    share.setShareUnits(inv.getShareUnits());
                    share.setPricePerUnit(inv.getPricePerUnit());
                    share.setPurchasedAt(OffsetDateTime.now());
                    shareRepository.save(share);

                    inv.setStatus("ACCEPTED");
                    invitationRepository.save(inv);

                    log.info("Allocated {} shares to user id={} from invitation",
                            inv.getShareUnits(), saved.getId());
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
                remaining
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
        return toDTO(userRepository.save(user));
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

    private void enforceActive(UserEntity user) {
        if ("SUSPENDED".equals(user.getStatus())) {
            throw new UserForbiddenException("Account is suspended.");
        }
        if ("PENDING".equals(user.getStatus())) {
            // PENDING with a cognitoId already set — shouldn't happen in normal flow
            throw new UserForbiddenException("Account is pending activation.");
        }
    }
}
