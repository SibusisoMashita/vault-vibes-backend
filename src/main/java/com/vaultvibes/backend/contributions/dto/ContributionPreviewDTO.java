package com.vaultvibes.backend.contributions.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Payment breakdown returned before a contribution is confirmed.
 *
 * contributionAmount      = shareUnits × sharePrice
 * loanOutstanding         = totalRepayment − amountRepaid  (0 if no active loan)
 * loanInterest            = flat interest on the loan principal (0 if no active loan)
 * repaymentAmount         = loanOutstanding  (full outstanding settled in one payment)
 * totalDue                = contributionAmount + repaymentAmount
 * activeLoanId            = null if the user has no active loan
 * hasContributedThisMonth = true if a non-rejected contribution already exists for the current month
 */
public record ContributionPreviewDTO(
        BigDecimal shareUnits,
        BigDecimal sharePrice,
        BigDecimal contributionAmount,
        BigDecimal loanOutstanding,
        BigDecimal loanInterest,
        BigDecimal repaymentAmount,
        BigDecimal totalDue,
        UUID activeLoanId,
        boolean hasContributedThisMonth
) {}
