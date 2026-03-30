package com.vaultvibes.backend.config;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Single access point for stokvel configuration values, scoped by stokvel ID.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StokvelConfigService {

    private static final BigDecimal DEFAULT_TOTAL_SHARES        = new BigDecimal("240");
    private static final BigDecimal DEFAULT_SHARE_PRICE         = new BigDecimal("5000.00");
    private static final BigDecimal DEFAULT_MONTHLY_CONTRIBUTION = new BigDecimal("500.00");
    private static final BigDecimal DEFAULT_INTEREST_RATE       = new BigDecimal("20.00");

    private final StokvelConfigRepository stokvelConfigRepository;
    private final BorrowingConfigRepository borrowingConfigRepository;

    /** Returns the config for the given stokvel, creating one with defaults if absent. */
    @Transactional
    public StokvelConfigEntity getOrCreate(UUID stokvelId) {
        return stokvelConfigRepository.findByStokvelId(stokvelId)
                .orElseGet(() -> {
                    StokvelConfigEntity cfg = new StokvelConfigEntity();
                    cfg.setStokvelId(stokvelId);
                    return stokvelConfigRepository.save(cfg);
                });
    }

    public BigDecimal getTotalShares(UUID stokvelId) {
        return stokvelConfigRepository.findByStokvelId(stokvelId)
                .map(StokvelConfigEntity::getTotalShares)
                .orElse(DEFAULT_TOTAL_SHARES);
    }

    public BigDecimal getSharePrice(UUID stokvelId) {
        return stokvelConfigRepository.findByStokvelId(stokvelId)
                .map(StokvelConfigEntity::getSharePrice)
                .orElse(DEFAULT_SHARE_PRICE);
    }

    public int getContributionMonths(UUID stokvelId) {
        return stokvelConfigRepository.findByStokvelId(stokvelId)
                .map(StokvelConfigEntity::getContributionMonths)
                .orElse(12);
    }

    public BigDecimal getInterestRate(UUID stokvelId) {
        return borrowingConfigRepository.findByStokvelId(stokvelId)
                .map(BorrowingConfigEntity::getInterestRate)
                .orElse(DEFAULT_INTEREST_RATE);
    }
}
