package com.vaultvibes.backend.pool;

import com.vaultvibes.backend.config.StokvelConfigRepository;
import com.vaultvibes.backend.ledger.LedgerEntryRepository;
import com.vaultvibes.backend.loans.LoanRepository;
import com.vaultvibes.backend.pool.dto.PoolStatsDTO;
import com.vaultvibes.backend.shares.ShareRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PoolService {

    private static final BigDecimal DEFAULT_TOTAL_SHARES = new BigDecimal("240");
    private static final BigDecimal DEFAULT_SHARE_PRICE  = new BigDecimal("5000.00");

    private final LedgerEntryRepository ledgerEntryRepository;
    private final LoanRepository loanRepository;
    private final ShareRepository shareRepository;
    private final StokvelConfigRepository stokvelConfigRepository;

    public PoolStatsDTO getStats() {
        BigDecimal totalSharesCap = stokvelConfigRepository.findAll()
                .stream().findFirst()
                .map(c -> c.getTotalShares())
                .orElse(DEFAULT_TOTAL_SHARES);

        // Authoritative ledger-based pool accounting
        BigDecimal bankBalance      = ledgerEntryRepository.sumAllLedgerAmounts();
        BigDecimal outstandingLoans = loanRepository.sumOutstandingLoansBalance();
        BigDecimal totalPoolValue   = bankBalance.add(outstandingLoans);
        BigDecimal liquidityAvailable = bankBalance;
        long activeLoans            = loanRepository.countActiveLoans();

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

        BigDecimal perShareValue = sharesSold.compareTo(BigDecimal.ZERO) > 0
                ? totalPoolValue.divide(sharesSold, 2, RoundingMode.HALF_UP)
                : pricePerShare;

        return new PoolStatsDTO(
                totalPoolValue,         // totalBalance — total stokvel value
                capitalCommitted,
                bankBalance,            // capitalReceived — actual cash in bank
                liquidityAvailable,     // available to issue new loans
                activeLoans,
                outstandingLoans,       // totalLoansValue — outstanding loan balance
                perShareValue,
                totalSharesCap,
                sharesSold,
                sharesAvailable,
                pricePerShare
        );
    }
}
