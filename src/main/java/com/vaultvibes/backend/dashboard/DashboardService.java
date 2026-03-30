package com.vaultvibes.backend.dashboard;

import com.vaultvibes.backend.config.StokvelConfigEntity;
import com.vaultvibes.backend.config.StokvelConfigService;
import com.vaultvibes.backend.contributions.ContributionRepository;
import com.vaultvibes.backend.dashboard.dto.DashboardSummaryDTO;
import com.vaultvibes.backend.ledger.LedgerEntryRepository;
import com.vaultvibes.backend.loans.LoanRepository;
import com.vaultvibes.backend.shares.ShareRepository;
import com.vaultvibes.backend.stokvels.StokvelsService;
import com.vaultvibes.backend.users.UserEntity;
import com.vaultvibes.backend.users.UserRepository;
import com.vaultvibes.backend.util.FinanceUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class DashboardService {

    private static final String YEAR_END          = "2026-12-31";
    private static final BigDecimal COLLATERAL_RATIO = new BigDecimal("0.50");
    private static final BigDecimal LIQUIDITY_RATIO  = new BigDecimal("0.50");

    private final UserRepository userRepository;
    private final ShareRepository shareRepository;
    private final ContributionRepository contributionRepository;
    private final LoanRepository loanRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final StokvelConfigService configService;
    private final StokvelsService stokvelsService;

    public DashboardSummaryDTO getSummary(UserEntity user) {
        UUID stokvelId = user.getStokvelId();
        StokvelConfigEntity cfg = configService.getOrCreate(stokvelId);

        BigDecimal totalSharesCap      = cfg.getTotalShares();
        BigDecimal monthlyContribution = cfg.getMonthlyContribution();
        int        cycleMonths         = cfg.getCycleMonths();
        LocalDate  cycleStartDate      = cfg.getCycleStartDate();

        String stokvelName = stokvelsService.getNameById(stokvelId);

        // ── User-level metrics ────────────────────────────────────────────────
        BigDecimal sharesOwned = shareRepository.sumShareUnitsByUserId(user.getId());
        BigDecimal paidSoFar   = contributionRepository.sumAmountByUserId(user.getId());

        BigDecimal totalCommitment = sharesOwned
                .multiply(monthlyContribution)
                .multiply(BigDecimal.valueOf(cycleMonths))
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal remaining = totalCommitment.subtract(paidSoFar).max(BigDecimal.ZERO);

        long monthsElapsed = Math.min(
                ChronoUnit.MONTHS.between(cycleStartDate, LocalDate.now()),
                cycleMonths);
        BigDecimal expectedToDate = sharesOwned
                .multiply(monthlyContribution)
                .multiply(BigDecimal.valueOf(Math.max(monthsElapsed, 0)))
                .setScale(2, RoundingMode.HALF_UP);

        if (paidSoFar.compareTo(totalCommitment) > 0) {
            log.warn("DATA_INTEGRITY_WARNING: user={} paidSoFar={} exceeds totalCommitment={}",
                    user.getId(), paidSoFar, totalCommitment);
        }

        // ── Pool-level metrics (scoped to stokvel) ───────────────────────────
        BigDecimal bankBalance      = ledgerEntryRepository.sumAllLedgerAmountsByStokvelId(stokvelId);
        BigDecimal outstandingLoans = loanRepository.sumOutstandingLoansBalanceByStokvelId(stokvelId);
        BigDecimal totalPoolValue   = FinanceUtil.calculatePoolValue(bankBalance, outstandingLoans);
        long activeLoans            = loanRepository.countActiveLoansByStokvelId(stokvelId);

        // ── Share metrics (scoped to stokvel) ─────────────────────────────────
        BigDecimal sharesSold    = shareRepository.sumAllShareUnitsByStokvelId(stokvelId);
        BigDecimal pricePerShare = shareRepository.avgPricePerUnitByStokvelId(stokvelId);
        if (pricePerShare.compareTo(BigDecimal.ZERO) == 0) {
            pricePerShare = cfg.getSharePrice();
        }
        BigDecimal capitalCommitted = FinanceUtil.calculateCapitalCommitted(sharesSold, pricePerShare);
        BigDecimal sharesAvailable  = totalSharesCap.subtract(sharesSold).max(BigDecimal.ZERO);

        BigDecimal perShareValue  = FinanceUtil.calculateShareValue(totalPoolValue, sharesSold, pricePerShare);
        BigDecimal estimatedValue = FinanceUtil.calculateMemberValue(sharesOwned, perShareValue);

        // ── Borrowing limit ───────────────────────────────────────────────────
        BigDecimal userOutstanding   = loanRepository.sumOutstandingLoansByUserId(user.getId());
        BigDecimal memberBorrowLimit = FinanceUtil.calculateMemberBorrowLimit(
                estimatedValue, userOutstanding, COLLATERAL_RATIO);
        BigDecimal poolBorrowLimit   = FinanceUtil.calculatePoolBorrowLimit(
                bankBalance, outstandingLoans, LIQUIDITY_RATIO);
        BigDecimal availableToBorrow = memberBorrowLimit.min(poolBorrowLimit);

        int totalMembers = (int) userRepository.countByStokvelId(stokvelId);

        return new DashboardSummaryDTO(
                sharesOwned,
                totalCommitment,
                paidSoFar,
                remaining,
                estimatedValue,
                perShareValue,
                totalPoolValue,
                capitalCommitted,
                bankBalance,
                bankBalance,
                outstandingLoans,
                activeLoans,
                totalSharesCap,
                sharesSold,
                sharesAvailable,
                pricePerShare,
                monthlyContribution,
                cycleMonths,
                expectedToDate,
                totalMembers,
                YEAR_END,
                stokvelName,
                bankBalance,
                outstandingLoans,
                estimatedValue,
                memberBorrowLimit,
                poolBorrowLimit,
                availableToBorrow
        );
    }
}
