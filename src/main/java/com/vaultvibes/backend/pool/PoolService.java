package com.vaultvibes.backend.pool;

import com.vaultvibes.backend.config.StokvelConfigService;
import com.vaultvibes.backend.ledger.LedgerEntryRepository;
import com.vaultvibes.backend.loans.LoanRepository;
import com.vaultvibes.backend.pool.dto.PoolStatsDTO;
import com.vaultvibes.backend.shares.ShareRepository;
import com.vaultvibes.backend.users.UserService;
import com.vaultvibes.backend.util.FinanceUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PoolService {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final LoanRepository loanRepository;
    private final ShareRepository shareRepository;
    private final StokvelConfigService configService;
    private final UserService userService;

    public PoolStatsDTO getStats() {
        UUID stokvelId = userService.getCurrentUser().getStokvelId();

        BigDecimal totalSharesCap = configService.getTotalShares(stokvelId);

        BigDecimal bankBalance      = ledgerEntryRepository.sumAllLedgerAmountsByStokvelId(stokvelId);
        BigDecimal outstandingLoans = loanRepository.sumOutstandingLoansBalanceByStokvelId(stokvelId);
        BigDecimal totalPoolValue   = FinanceUtil.calculatePoolValue(bankBalance, outstandingLoans);
        long activeLoans            = loanRepository.countActiveLoansByStokvelId(stokvelId);

        BigDecimal sharesSold    = shareRepository.sumAllShareUnitsByStokvelId(stokvelId);
        BigDecimal pricePerShare = resolveEffectiveSharePrice(
                shareRepository.avgPricePerUnitByStokvelId(stokvelId), stokvelId);
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

    private BigDecimal resolveEffectiveSharePrice(BigDecimal avgPrice, UUID stokvelId) {
        return avgPrice.compareTo(BigDecimal.ZERO) == 0
                ? configService.getSharePrice(stokvelId)
                : avgPrice;
    }
}
