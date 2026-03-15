package com.vaultvibes.backend.loans.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record LoanDTO(
        UUID id,
        UUID userId,
        String memberName,
        BigDecimal principalAmount,
        BigDecimal interestRate,
        BigDecimal interest,
        BigDecimal totalRepayment,
        BigDecimal amountRepaid,
        BigDecimal remaining,
        String status,
        OffsetDateTime issuedAt,
        OffsetDateTime dueAt,
        OffsetDateTime createdAt
) {}
