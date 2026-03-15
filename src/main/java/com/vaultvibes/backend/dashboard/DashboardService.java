package com.vaultvibes.backend.dashboard;

import com.vaultvibes.backend.config.StokvelConfigRepository;
import com.vaultvibes.backend.contributions.ContributionRepository;
import com.vaultvibes.backend.dashboard.dto.DashboardSummaryDTO;
import com.vaultvibes.backend.ledger.LedgerEntryRepository;
import com.vaultvibes.backend.loans.LoanRepository;
import com.vaultvibes.backend.shares.ShareRepository;
import com.vaultvibes.backend.users.UserEntity;
import com.vaultvibes.backend.users.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private static final String GROUP_NAME           = "Vault Vibes";
    private static final String YEAR_END             = "2026-12-31";
    private static final BigDecimal DEFAULT_TOTAL_SHARES = new BigDecimal("240");
    private static final BigDecimal DEFAULT_SHARE_PRICE  = new BigDecimal("5000.00");

    private final UserRepository userRepository;
    private final ShareRepository shareRepository;
    private final ContributionRepository contributionRepository;
    private final LoanRepository loanRepository;
    private final StokvelConfigRepository stokvelConfigRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public DashboardSummaryDTO getSummary(UserEntity user) {
        BigDecimal totalSharesCap = stokvelConfigRepository.findAll()
                .stream().findFirst()
                .map(c -> c.getTotalShares())
                .orElse(DEFAULT_TOTAL_SHARES);

        // User-level metrics — contribution table tracks individual member payment history
        BigDecimal sharesOwned     = shareRepository.sumShareUnitsByUserId(user.getId());
        BigDecimal totalCommitment = shareRepository.sumCommitmentByUserId(user.getId());
        BigDecimal paidSoFar       = contributionRepository.sumAmountByUserId(user.getId());
        BigDecimal remaining       = totalCommitment.subtract(paidSoFar).max(BigDecimal.ZERO);

        // Pool-level metrics — all derived from the authoritative signed ledger
        // bank_balance = SUM(ledger.amount): contributions(+), loan_issued(-),
        //                                    loan_repayment(+), bank_interest(+)
        BigDecimal bankBalance      = ledgerEntryRepository.sumAllLedgerAmounts();
        // outstanding_loans = money lent out that still belongs to the pool
        BigDecimal outstandingLoans = loanRepository.sumOutstandingLoansBalance();
        // total_pool_value = everything the stokvel owns (cash + what members owe back)
        BigDecimal totalPoolValue   = bankBalance.add(outstandingLoans);
        // liquidity = actual cash in the bank, available for new loans
        BigDecimal liquidityAvailable = bankBalance;
        long activeLoans            = loanRepository.countActiveLoans();

        // Capital metrics
        BigDecimal sharesSold    = shareRepository.sumAllShareUnits();
        BigDecimal pricePerShare = shareRepository.avgPricePerUnit();
        if (pricePerShare.compareTo(BigDecimal.ZERO) == 0) {
            pricePerShare = stokvelConfigRepository.findAll()
                    .stream().findFirst()
                    .map(c -> c.getSharePrice())
                    .orElse(DEFAULT_SHARE_PRICE);
        }
        BigDecimal capitalCommitted = sharesSold.multiply(pricePerShare).setScale(2, RoundingMode.HALF_UP);
        BigDecimal sharesAvailable  = totalSharesCap.subtract(sharesSold).max(BigDecimal.ZERO);

        // per_share_value = total pool value / shares — reflects bank balance + outstanding loans
        BigDecimal perShareValue = sharesSold.compareTo(BigDecimal.ZERO) > 0
                ? totalPoolValue.divide(sharesSold, 2, RoundingMode.HALF_UP)
                : pricePerShare;
        BigDecimal estimatedValue = sharesOwned.multiply(perShareValue).setScale(2, RoundingMode.HALF_UP);

        // --- Borrowing limit calculation ---
        // 1. Member collateral rule: 50% of share value minus user's own outstanding loans
        BigDecimal memberShareValue  = sharesOwned.multiply(perShareValue).setScale(2, RoundingMode.HALF_UP);
        BigDecimal userOutstanding   = loanRepository.sumOutstandingLoansByUserId(user.getId());
        BigDecimal memberBorrowLimit = memberShareValue
                .multiply(new BigDecimal("0.50"))
                .subtract(userOutstanding)
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        // 2. Pool liquidity rule: stokvel must keep at least 50% of cash liquidity
        BigDecimal poolLimit = bankBalance.multiply(new BigDecimal("0.50")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal poolBorrowLimit = poolLimit.subtract(outstandingLoans).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        // 3. Final borrow limit: MIN(member collateral after outstanding deduction, pool available)
        BigDecimal availableToBorrow = memberBorrowLimit.min(poolBorrowLimit);

        int totalMembers = (int) userRepository.count();

        return new DashboardSummaryDTO(
                sharesOwned,
                totalCommitment,
                paidSoFar,
                remaining,
                estimatedValue,
                perShareValue,
                totalPoolValue,         // "Group Pool" — total stokvel value
                capitalCommitted,
                bankBalance,            // "Capital Received" — actual cash in bank
                liquidityAvailable,     // available to issue new loans
                outstandingLoans,       // "Total Loans Value" — outstanding loan balance
                activeLoans,
                totalSharesCap,
                sharesSold,
                sharesAvailable,
                pricePerShare,
                totalMembers,
                YEAR_END,
                GROUP_NAME,
                bankBalance,
                outstandingLoans,
                memberShareValue,
                memberBorrowLimit,
                poolBorrowLimit,
                availableToBorrow
        );
    }
}
