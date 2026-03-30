package com.vaultvibes.backend.pool;

import com.vaultvibes.backend.config.StokvelConfigService;
import com.vaultvibes.backend.ledger.LedgerEntryRepository;
import com.vaultvibes.backend.loans.LoanRepository;
import com.vaultvibes.backend.pool.dto.PoolProjectionDTO;
import com.vaultvibes.backend.shares.ShareRepository;
import com.vaultvibes.backend.users.UserService;
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
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PoolProjectionService {

    private static final String YEAR_END = "2026-12-31";
    private static final int BANK_INTEREST_WINDOW = 3;

    private final LedgerEntryRepository ledgerEntryRepository;
    private final LoanRepository loanRepository;
    private final ShareRepository shareRepository;
    private final StokvelConfigService configService;
    private final UserService userService;

    public PoolProjectionDTO getProjection() {
        UUID stokvelId = userService.getCurrentUser().getStokvelId();

        BigDecimal bankBalance      = ledgerEntryRepository.sumAllLedgerAmountsByStokvelId(stokvelId);
        BigDecimal outstandingLoans = loanRepository.sumOutstandingLoansBalanceByStokvelId(stokvelId);
        BigDecimal currentPoolValue = FinanceUtil.calculatePoolValue(bankBalance, outstandingLoans);

        BigDecimal sharesSold        = shareRepository.sumAllShareUnitsByStokvelId(stokvelId);
        BigDecimal sharePrice        = configService.getSharePrice(stokvelId);
        int        contributionMonths = configService.getContributionMonths(stokvelId);

        long monthsRemaining = Math.max(0L,
                ChronoUnit.MONTHS.between(LocalDate.now(), LocalDate.parse(YEAR_END)));

        BigDecimal monthlyPoolContribution = FinanceUtil.calculateContributionAmount(sharesSold, sharePrice);
        BigDecimal contributionsRemaining = monthlyPoolContribution
                .multiply(BigDecimal.valueOf(monthsRemaining))
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal expectedLoanInterest = loanRepository
                .findByStatusInAndStokvelId(List.of("ACTIVE"), stokvelId)
                .stream()
                .map(loan -> FinanceUtil.calculateFlatInterest(loan.getPrincipalAmount(), loan.getInterestRate()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal avgMonthlyBankInterest = computeAvgMonthlyBankInterest(stokvelId);
        BigDecimal projectedBankInterest = avgMonthlyBankInterest
                .multiply(BigDecimal.valueOf(monthsRemaining))
                .setScale(2, RoundingMode.HALF_UP);

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
                projectedPerShareValue,
                sharePrice,
                contributionMonths
        );
    }

    private BigDecimal computeAvgMonthlyBankInterest(UUID stokvelId) {
        LocalDate since = LocalDate.now().minusMonths(BANK_INTEREST_WINDOW);
        BigDecimal total = ledgerEntryRepository.sumBankInterestSinceByStokvelId(
                since.atStartOfDay().atOffset(ZoneOffset.UTC), stokvelId);
        if (total.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return total.divide(new BigDecimal(BANK_INTEREST_WINDOW), 2, RoundingMode.HALF_UP);
    }
}
