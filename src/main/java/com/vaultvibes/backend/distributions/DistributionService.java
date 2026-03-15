package com.vaultvibes.backend.distributions;

import com.vaultvibes.backend.distributions.dto.DistributionDTO;
import com.vaultvibes.backend.notifications.NotificationEventDetail;
import com.vaultvibes.backend.notifications.NotificationEventService;
import com.vaultvibes.backend.notifications.NotificationEventType;
import com.vaultvibes.backend.users.UserEntity;
import com.vaultvibes.backend.users.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class DistributionService {

    private final DistributionRepository distributionRepository;
    private final UserRepository userRepository;
    private final NotificationEventService notificationEventService;

    public List<DistributionDTO> listAll() {
        return distributionRepository.findAllByOrderByDistributedAtDesc().stream()
                .map(this::toDTO)
                .toList();
    }

    public List<DistributionDTO> listForUser(UUID userId) {
        log.info("Fetching distributions for user {}", userId);
        return distributionRepository.findByUserIdOrderByDistributedAtDesc(userId).stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * Records a payout distribution for a member and fires a DISTRIBUTION_EXECUTED
     * notification event to EventBridge.
     */
    @Transactional
    public DistributionDTO execute(UUID userId, BigDecimal amount, LocalDate periodStart, LocalDate periodEnd) {
        log.info("Executing distribution of {} for user {}", amount, userId);

        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        DistributionEntity distribution = new DistributionEntity();
        distribution.setUser(user);
        distribution.setAmount(amount);
        distribution.setPeriodStart(periodStart);
        distribution.setPeriodEnd(periodEnd);
        distribution.setDistributedAt(OffsetDateTime.now());

        DistributionEntity saved = distributionRepository.save(distribution);
        log.info("Distribution {} recorded for user {}", saved.getId(), user.getFullName());

        // Notify member that their distribution has been paid out
        notificationEventService.publish(
                NotificationEventType.DISTRIBUTION_EXECUTED,
                new NotificationEventDetail(user.getId(), user.getPhoneNumber(), amount));

        return toDTO(saved);
    }

    public DistributionDTO toDTO(DistributionEntity d) {
        return new DistributionDTO(
                d.getId(),
                d.getUser().getId(),
                d.getUser().getFullName(),
                d.getAmount(),
                d.getPeriodStart(),
                d.getPeriodEnd(),
                d.getDistributedAt()
        );
    }
}


