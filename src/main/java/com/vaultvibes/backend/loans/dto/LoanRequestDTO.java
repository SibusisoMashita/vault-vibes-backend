package com.vaultvibes.backend.loans.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record LoanRequestDTO(
        @NotNull UUID userId,
        @NotNull @DecimalMin("1.00") BigDecimal amount
) {}
