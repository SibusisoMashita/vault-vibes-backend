package com.vaultvibes.backend.distributions.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record DistributionDTO(
        UUID id,
        UUID userId,
        String memberName,
        BigDecimal amount,
        LocalDate periodStart,
        LocalDate periodEnd,
        OffsetDateTime distributedAt
) {}
