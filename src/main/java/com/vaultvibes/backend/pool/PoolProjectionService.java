package com.vaultvibes.backend.pool;

import com.vaultvibes.backend.config.StokvelConfigService;
import com.vaultvibes.backend.ledger.LedgerEntryRepository;
import com.vaultvibes.backend.loans.LoanRepository;
import com.vaultvibes.backend.pool.dto.PoolProjectionDTO;
import com.vaultvibes.backend.shares.ShareRepository;
import com.vaultvibes.backend.util.FinanceUtil;
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

    private static final String YEAR_END = "2026-12-31";
    /** Number of historical months used to compute the bank interest average. */
    private static final int BANK_INTEREST_WINDOW = 3;

    private final LedgerEntryRepository ledgerEntryRepository;
    private final LoanRepository loanRepository;
    private final ShareRepository shareRepository;
    private final StokvelConfigService configService;

    public PoolProjectionDTO getProjection() {

        // Current pool state
        BigDecimal bankBalance      = ledgerEntryRepository.sumAllLedgerAmounts();
        BigDecimal outstandingLoans = loanRepository.sumOutstandingLoansBalance();
        BigDecimal currentPoolValue = FinanceUtil.calculatePoolValue(bankBalance, outstandingLoans);

        BigDecimal sharesSold = shareRepository.sumAllShareUnits();
        BigDecimal sharePrice = configService.getSharePrice();

        // Months remaining until year-end distribution
        long monthsRemaining = Math.max(0L,
                ChronoUnit.MONTHS.between(LocalDate.now(), LocalDate.parse(YEAR_END)));

        // Contributions projection: all shares × share price × months remaining
        BigDecimal monthlyPoolContribution = FinanceUtil.calculateContributionAmount(sharesSold, sharePrice);
        BigDecimal contributionsRemaining = monthlyPoolContribution
                .multiply(BigDecimal.valueOf(monthsRemaining))
                .setScale(2, RoundingMode.HALF_UP);

        // Expected loan interest from active loans
        BigDecimal expectedLoanInterest = loanRepository
                .findByStatusIn(List.of("ACTIVE"))
                .stream()
                .map(loan -> FinanceUtil.calculateFlatInterest(loan.getPrincipalAmount(), loan.getInterestRate()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        // Bank interest projection (rolling historical average)
        BigDecimal avgMonthlyBankInterest = computeAvgMonthlyBankInterest();
        BigDecimal projectedBankInterest = avgMonthlyBankInterest
                .multiply(BigDecimal.valueOf(monthsRemaining))
                .setScale(2, RoundingMode.HALF_UP);

        // Projected pool value
        BigDecimal projectedPoolValue = currentPoolValue
                .add(contributionsRemaining)
                .add(expectedLoanInterest)
                .add(projectedBankInterest);

        BigDecimal projectedPerShareValue = FinanceUtil.calculateShareValue(
                projectedPoolValue, sharesSold, sharePrice);

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

    /**
     * Averages BANK_INTEREST ledger entries over the past {@value BANK_INTEREST_WINDOW} months.
     */
    private BigDecimal computeAvgMonthlyBankInterest() {
        LocalDate since = LocalDate.now().minusMonths(BANK_INTEREST_WINDOW);
        BigDecimal total = ledgerEntryRepository.sumBankInterestSince(
                since.atStartOfDay().atOffset(ZoneOffset.UTC));
        if (total.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return total.divide(new BigDecimal(BANK_INTEREST_WINDOW), 2, RoundingMode.HALF_UP);
    }
}
