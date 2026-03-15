package com.vaultvibes.backend.invitations;

import com.vaultvibes.backend.auth.CurrentUserService;
import com.vaultvibes.backend.config.StokvelConfigRepository;
import com.vaultvibes.backend.invitations.dto.InvitationDTO;
import com.vaultvibes.backend.notifications.NotificationEventDetail;
import com.vaultvibes.backend.notifications.NotificationEventService;
import com.vaultvibes.backend.notifications.NotificationEventType;
import com.vaultvibes.backend.shares.ShareRepository;
import com.vaultvibes.backend.users.UserEntity;
import com.vaultvibes.backend.users.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * Manages stokvel invitations.
 *
 * Cognito is responsible for sending the invitation SMS and managing authentication.
 * This service only manages the database records: invitation + pre-created user.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class InvitationService {

    private static final BigDecimal DEFAULT_TOTAL_SHARES = new BigDecimal("20");
    private static final BigDecimal DEFAULT_SHARE_PRICE  = new BigDecimal("1000.00");

    private final InvitationRepository    invitationRepository;
    private final UserRepository          userRepository;
    private final ShareRepository         shareRepository;
    private final StokvelConfigRepository stokvelConfigRepository;
    private final CurrentUserService      currentUserService;
    private final NotificationEventService notificationEventService;

    public List<InvitationDTO> listAll() {
        return invitationRepository.findAll().stream()
                .map(this::toDTO)
                .toList();
    }

    @Transactional
    public InvitationDTO createInvitation(String phoneNumber, String role, BigDecimal shareUnits) {
        // Validate share units
        if (shareUnits == null || shareUnits.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("A member must be assigned at least part of a share.");
        }

        // Prevent duplicate pending invitations
        invitationRepository.findByPhoneNumber(phoneNumber).stream()
                .filter(i -> "PENDING".equals(i.getStatus()))
                .findFirst()
                .ifPresent(i -> {
                    throw new IllegalStateException("A pending invitation already exists for " + phoneNumber);
                });

        // Reject if a user with this phone is already active
        userRepository.findByPhoneNumber(phoneNumber).ifPresent(u -> {
            if ("ACTIVE".equals(u.getStatus())) {
                throw new IllegalStateException(
                        "A user with phone number " + phoneNumber + " is already active.");
            }
        });

        // Load stokvel config for pricing and share limit
        BigDecimal totalShares = stokvelConfigRepository.findAll().stream()
                .findFirst().map(c -> c.getTotalShares()).orElse(DEFAULT_TOTAL_SHARES);

        BigDecimal pricePerUnit = stokvelConfigRepository.findAll().stream()
                .findFirst().map(c -> c.getSharePrice()).orElse(DEFAULT_SHARE_PRICE);

        // Validate available shares: total − assigned − pending invitations
        BigDecimal assignedShares = shareRepository.sumAllShareUnits();
        BigDecimal pendingShares  = invitationRepository.sumPendingShareUnits();
        BigDecimal available      = totalShares.subtract(assignedShares).subtract(pendingShares);

        if (shareUnits.compareTo(available) > 0) {
            throw new IllegalArgumentException(
                    "Not enough shares available. Requested: " + shareUnits
                    + ", available: " + available.setScale(4, RoundingMode.HALF_UP));
        }

        // Resolve inviter from the current session
        UUID invitedBy = resolveInviterId();

        // Pre-create user record so they can link their Cognito identity on first login
        userRepository.findByPhoneNumber(phoneNumber).orElseGet(() -> {
            UserEntity pending = new UserEntity();
            pending.setPhoneNumber(phoneNumber);
            pending.setFullName(phoneNumber); // updated by the user after their first login
            pending.setRole(role.toUpperCase());
            pending.setStatus("PENDING");
            UserEntity saved = userRepository.save(pending);
            log.info("Pre-created PENDING user id={} for phone_number={}", saved.getId(), phoneNumber);
            return saved;
        });

        // Persist the invitation record
        InvitationEntity invitation = new InvitationEntity();
        invitation.setPhoneNumber(phoneNumber);
        invitation.setRole(role.toUpperCase());
        invitation.setInvitedBy(invitedBy);
        invitation.setShareUnits(shareUnits);
        invitation.setPricePerUnit(pricePerUnit);
        InvitationEntity saved = invitationRepository.save(invitation);

        // Notify admins that a new member has been invited
        notificationEventService.publish(
                NotificationEventType.MEMBER_INVITED,
                new NotificationEventDetail(null, phoneNumber,
                        shareUnits.multiply(pricePerUnit)));

        // Cognito handles sending the SMS invite — no action required here
        log.info("Invitation created id={} phone={} role={} shares={}",
                saved.getId(), phoneNumber, role, shareUnits);
        return toDTO(saved);
    }

    private UUID resolveInviterId() {
        String cognitoId = currentUserService.getCurrentUserId();
        if (cognitoId == null) return null;
        return userRepository.findByCognitoId(cognitoId)
                .map(u -> u.getId())
                .orElse(null);
    }

    private InvitationDTO toDTO(InvitationEntity e) {
        return new InvitationDTO(
                e.getId(),
                e.getPhoneNumber(),
                e.getRole(),
                e.getInvitedBy(),
                e.getShareUnits(),
                e.getPricePerUnit(),
                e.getStatus(),
                e.getCreatedAt()
        );
    }
}
