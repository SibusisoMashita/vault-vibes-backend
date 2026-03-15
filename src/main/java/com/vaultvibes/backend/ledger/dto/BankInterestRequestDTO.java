package com.vaultvibes.backend.ledger.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BankInterestRequestDTO(
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotNull LocalDate postedAt,
        String reference,
        String description
) {}
