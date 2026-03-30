package com.vaultvibes.backend.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Centralized financial calculations for the Vault Vibes stokvel.
 *
 * All pool valuation, share pricing, and interest formulas live here.
 * Controllers and services must never implement these calculations directly.
 *
 * Core formulas:
 *   Pool Value   = Bank Balance (ledger sum) + Outstanding Loans
 *   Share Value  = Pool Value / Funded Shares
 *   Member Value = Shares Owned × Share Value
 *   Profit       = Member Value − Contributions Paid
 *   Interest     = Principal × (Rate / 100)
 */
public final class FinanceUtil {

    private FinanceUtil() {}

    /**
     * Pool Value = bank balance (ledger sum).
     * Outstanding loans are tracked separately for borrowing limits but are not
     * added here — the pool reflects what is actually held in the bank account.
     */
    public static BigDecimal calculatePoolValue(BigDecimal bankBalance, BigDecimal outstandingLoans) {
        return bankBalance;
    }

    /**
     * Share Value = Pool Value / Total Funded Shares.
     * Falls back to the configured share price if no shares have been sold.
     */
    public static BigDecimal calculateShareValue(BigDecimal poolValue, BigDecimal sharesSold,
                                                  BigDecimal fallbackPrice) {
        if (sharesSold.compareTo(BigDecimal.ZERO) > 0) {
            return poolValue.divide(sharesSold, 4, RoundingMode.HALF_UP);
        }
        return fallbackPrice;
    }

    /**
     * Member Value = shares owned × per-share value.
     */
    public static BigDecimal calculateMemberValue(BigDecimal sharesOwned, BigDecimal perShareValue) {
        return sharesOwned.multiply(perShareValue).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Profit = member value − total contributions paid.
     */
    public static BigDecimal calculateProfit(BigDecimal memberValue, BigDecimal contributionsPaid) {
        return memberValue.subtract(contributionsPaid).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Flat simple interest: principal × (rate / 100).
     */
    public static BigDecimal calculateFlatInterest(BigDecimal principal, BigDecimal ratePercent) {
        return principal
                .multiply(ratePercent)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }

    /**
     * Capital committed = shares sold × price per share.
     */
    public static BigDecimal calculateCapitalCommitted(BigDecimal sharesSold, BigDecimal pricePerShare) {
        return sharesSold.multiply(pricePerShare).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Contribution amount = share units × share price.
     */
    public static BigDecimal calculateContributionAmount(BigDecimal shareUnits, BigDecimal sharePrice) {
        return shareUnits.multiply(sharePrice).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Borrowing limit for a member: 50% of their share value minus outstanding loans.
     */
    public static BigDecimal calculateMemberBorrowLimit(BigDecimal memberShareValue,
                                                         BigDecimal userOutstanding,
                                                         BigDecimal collateralRatio) {
        return memberShareValue
                .multiply(collateralRatio)
                .subtract(userOutstanding)
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Pool-level borrowing limit: pool must retain a minimum ratio of cash liquidity.
     */
    public static BigDecimal calculatePoolBorrowLimit(BigDecimal bankBalance,
                                                       BigDecimal outstandingLoans,
                                                       BigDecimal liquidityRatio) {
        BigDecimal poolLimit = bankBalance.multiply(liquidityRatio).setScale(2, RoundingMode.HALF_UP);
        return poolLimit.subtract(outstandingLoans).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }
}
