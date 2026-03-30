package com.vaultvibes.backend.dashboard.dto;

import java.math.BigDecimal;

public record DashboardSummaryDTO(
        // User metrics
        BigDecimal sharesOwned,
        BigDecimal totalCommitment,
        BigDecimal paidSoFar,
        BigDecimal remaining,
        BigDecimal estimatedValue,

        // Pool metrics
        BigDecimal perShareValue,
        BigDecimal poolBalance,
        BigDecimal capitalCommitted,
        BigDecimal capitalReceived,
        BigDecimal liquidityAvailable,
        BigDecimal totalLoansValue,
        long activeLoans,

        // Share metrics
        BigDecimal totalShares,
        BigDecimal sharesSold,
        BigDecimal sharesAvailable,
        BigDecimal pricePerShare,

        // Cycle info
        BigDecimal monthlyContribution,
        int cycleMonths,
        BigDecimal expectedToDate,

        // Group info
        int totalMembers,
        String yearEnd,
        String stokvelName,

        // Borrowing limits
        BigDecimal bankBalance,
        BigDecimal outstandingLoans,
        BigDecimal memberShareValue,
        BigDecimal memberBorrowLimit,
        BigDecimal poolBorrowLimit,
        BigDecimal availableToBorrow
) {}
