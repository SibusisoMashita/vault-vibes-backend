package com.vaultvibes.backend.shares.dto;

import java.math.BigDecimal;

public record ShareSummaryDTO(
        BigDecimal totalShares,
        BigDecimal sharesSold,
        BigDecimal sharesAvailable,
        BigDecimal pricePerShare
) {}
