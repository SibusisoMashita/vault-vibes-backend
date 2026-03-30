package com.vaultvibes.backend.pool.dto;

import java.math.BigDecimal;

/**
 * Projection model for the year-end distribution.
 *
 * projectedPoolValue =
 *     currentPoolValue
 *   + contributionsRemaining   (SUM(shares) × sharePrice × monthsRemaining)
 *   + expectedLoanInterest     (flat interest still owed on all active loans)
 *   + projectedBankInterest    (avg monthly bank interest × monthsRemaining)
 *
 * All values are estimates. Bank interest is extrapolated from the most recent
 * 3 months of BANK_INTEREST ledger entries.
 */
public record PoolProjectionDTO(
        BigDecimal currentPoolValue,
        long       monthsRemaining,
        BigDecimal monthlyPoolContribution,
        BigDecimal contributionsRemaining,
        BigDecimal expectedLoanInterest,
        BigDecimal avgMonthlyBankInterest,
        BigDecimal projectedBankInterest,
        BigDecimal projectedPoolValue,
        BigDecimal projectedPerShareValue,
        /** Monthly share price — used by frontend to calculate per-member total annual commitment. */
        BigDecimal sharePrice,
        /** Contribution months configured for this group (e.g. 11 if skipping December). */
        int        contributionMonths
) {}
