package com.vaultvibes.backend.pool;

import com.vaultvibes.backend.config.StokvelConfigService;
import com.vaultvibes.backend.ledger.LedgerEntryRepository;
import com.vaultvibes.backend.loans.LoanRepository;
import com.vaultvibes.backend.pool.dto.PoolStatsDTO;
import com.vaultvibes.backend.shares.ShareRepository;
import com.vaultvibes.backend.util.FinanceUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PoolService {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final LoanRepository loanRepository;
    private final ShareRepository shareRepository;
    private final StokvelConfigService configService;

    public PoolStatsDTO getStats() {
        BigDecimal totalSharesCap = configService.getTotalShares();

        BigDecimal bankBalance      = ledgerEntryRepository.sumAllLedgerAmounts();
        BigDecimal outstandingLoans = loanRepository.sumOutstandingLoansBalance();
        BigDecimal totalPoolValue   = FinanceUtil.calculatePoolValue(bankBalance, outstandingLoans);
        long activeLoans            = loanRepository.countActiveLoans();

        BigDecimal sharesSold    = shareRepository.sumAllShareUnits();
        BigDecimal pricePerShare = resolveEffectiveSharePrice(shareRepository.avgPricePerUnit());
        BigDecimal capitalCommitted = FinanceUtil.calculateCapitalCommitted(sharesSold, pricePerShare);
        BigDecimal sharesAvailable  = totalSharesCap.subtract(sharesSold).max(BigDecimal.ZERO);

        BigDecimal perShareValue = FinanceUtil.calculateShareValue(totalPoolValue, sharesSold, pricePerShare);

        return new PoolStatsDTO(
                totalPoolValue,
                capitalCommitted,
                bankBalance,
                bankBalance,
                activeLoans,
                outstandingLoans,
                perShareValue,
                totalSharesCap,
                sharesSold,
                sharesAvailable,
                pricePerShare
        );
    }

    /**
     * If no shares exist yet, the average price is zero — fall back to the configured price.
     */
    private BigDecimal resolveEffectiveSharePrice(BigDecimal avgPrice) {
        return avgPrice.compareTo(BigDecimal.ZERO) == 0 ? configService.getSharePrice() : avgPrice;
    }
}
