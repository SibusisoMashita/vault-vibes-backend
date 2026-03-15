package com.vaultvibes.backend.contributions.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ContributionDTO(
        UUID id,
        UUID userId,
        String memberName,
        BigDecimal amount,
        LocalDate contributionDate,
        String notes,
        String proofFileType,
        boolean proofFileAvailable,
        String verificationStatus,
        String rejectionReason,
        OffsetDateTime createdAt
) {}
