package com.vaultvibes.backend.pool.dto;

import java.math.BigDecimal;

public record PoolStatsDTO(
        BigDecimal totalBalance,
        BigDecimal capitalCommitted,
        BigDecimal capitalReceived,
        BigDecimal liquidityAvailable,
        long activeLoans,
        BigDecimal totalLoansValue,
        BigDecimal perShareValue,
        BigDecimal totalShares,
        BigDecimal sharesSold,
        BigDecimal sharesAvailable,
        BigDecimal pricePerShare
) {}
