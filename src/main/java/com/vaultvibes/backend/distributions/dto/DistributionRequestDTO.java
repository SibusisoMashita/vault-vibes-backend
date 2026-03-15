package com.vaultvibes.backend.distributions.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request body for POST /api/distributions — records a payout for a single member.
 */
public record DistributionRequestDTO(
        @NotNull UUID userId,
        @NotNull @Positive BigDecimal amount,
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd
) {}

