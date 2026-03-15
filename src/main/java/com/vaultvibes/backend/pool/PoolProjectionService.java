package com.vaultvibes.backend.pool;

import com.vaultvibes.backend.config.StokvelConfigEntity;
import com.vaultvibes.backend.config.StokvelConfigRepository;
import com.vaultvibes.backend.ledger.LedgerEntryRepository;
import com.vaultvibes.backend.loans.LoanEntity;
import com.vaultvibes.backend.loans.LoanRepository;
import com.vaultvibes.backend.pool.dto.PoolProjectionDTO;
import com.vaultvibes.backend.shares.ShareRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PoolProjectionService {

    private static final String    YEAR_END           = "2026-12-31";
    private static final BigDecimal DEFAULT_SHARE_PRICE = new BigDecimal("5000.00");
    /** Number of historical months used to compute the bank interest average. */
    private static final int       BANK_INTEREST_WINDOW = 3;

    private final LedgerEntryRepository ledgerEntryRepository;
    private final LoanRepository        loanRepository;
    private final ShareRepository       shareRepository;
    private final StokvelConfigRepository stokvelConfigRepository;

    public PoolProjectionDTO getProjection() {

        // ── 1. Current pool state (mirrors PoolService) ────────────────────
        BigDecimal bankBalance      = ledgerEntryRepository.sumAllLedgerAmounts();
        BigDecimal outstandingLoans = loanRepository.sumOutstandingLoansBalance();
        BigDecimal currentPoolValue = bankBalance.add(outstandingLoans);

        BigDecimal sharesSold  = shareRepository.sumAllShareUnits();
        BigDecimal sharePrice  = resolveSharePrice();

        // ── 2. Months remaining until year-end distribution ─────────────────
        long monthsRemaining = Math.max(0L,
                ChronoUnit.MONTHS.between(LocalDate.now(), LocalDate.parse(YEAR_END)));

        // ── 3. Contributions projection ─────────────────────────────────────
        // Every member contributes share_units × share_price each month.
        // Pool-level total = SUM(all share_units) × share_price × months_remaining.
        BigDecimal monthlyPoolContribution = sharesSold
                .multiply(sharePrice)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal contributionsRemaining = monthlyPoolContribution
                .multiply(BigDecimal.valueOf(monthsRemaining))
                .setScale(2, RoundingMode.HALF_UP);

        // ── 4. Expected loan interest ────────────────────────────────────────
        // The pool gains the interest component when active loans are repaid.
        // Outstanding principal is already included in currentPoolValue, so only
        // the interest portion represents additional pool growth.
        BigDecimal expectedLoanInterest = loanRepository
                .findByStatusIn(List.of("ACTIVE"))
                .stream()
                .map(loan -> flatInterest(loan.getPrincipalAmount(), loan.getInterestRate()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        // ── 5. Bank interest projection (rolling historical average) ─────────
        BigDecimal avgMonthlyBankInterest = computeAvgMonthlyBankInterest();
        BigDecimal projectedBankInterest  = avgMonthlyBankInterest
                .multiply(BigDecimal.valueOf(monthsRemaining))
                .setScale(2, RoundingMode.HALF_UP);

        // ── 6. Projected pool value ──────────────────────────────────────────
        BigDecimal projectedPoolValue = currentPoolValue
                .add(contributionsRemaining)
                .add(expectedLoanInterest)
                .add(projectedBankInterest);

        // ── 7. Projected per-share value ─────────────────────────────────────
        BigDecimal projectedPerShareValue = sharesSold.compareTo(BigDecimal.ZERO) > 0
                ? projectedPoolValue.divide(sharesSold, 2, RoundingMode.HALF_UP)
                : sharePrice;

        return new PoolProjectionDTO(
                currentPoolValue,
                monthsRemaining,
                monthlyPoolContribution,
                contributionsRemaining,
                expectedLoanInterest,
                avgMonthlyBankInterest,
                projectedBankInterest,
                projectedPoolValue,
                projectedPerShareValue
        );
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Averages BANK_INTEREST ledger entries over the past {@value BANK_INTEREST_WINDOW} months.
     * If no entries exist, returns zero (projection simply omits bank interest).
     */
    private BigDecimal computeAvgMonthlyBankInterest() {
        LocalDate since  = LocalDate.now().minusMonths(BANK_INTEREST_WINDOW);
        BigDecimal total = ledgerEntryRepository.sumBankInterestSince(
                since.atStartOfDay().atOffset(ZoneOffset.UTC));
        if (total.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return total.divide(new BigDecimal(BANK_INTEREST_WINDOW), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveSharePrice() {
        return stokvelConfigRepository.findAll()
                .stream().findFirst()
                .map(StokvelConfigEntity::getSharePrice)
                .orElse(DEFAULT_SHARE_PRICE);
    }

    /** Flat simple interest: principal × (rate / 100). */
    private BigDecimal flatInterest(BigDecimal principal, BigDecimal rate) {
        return principal
                .multiply(rate)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }
}
