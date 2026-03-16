package com.vaultvibes.backend.config;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Single access point for stokvel configuration values.
 *
 * Eliminates the repeated {@code findAll().stream().findFirst().orElse(...)} pattern
 * that was scattered across DashboardService, PoolService, ShareService, etc.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StokvelConfigService {

    private static final BigDecimal DEFAULT_TOTAL_SHARES       = new BigDecimal("240");
    private static final BigDecimal DEFAULT_SHARE_PRICE        = new BigDecimal("5000.00");
    private static final BigDecimal DEFAULT_MONTHLY_CONTRIBUTION = new BigDecimal("500.00");
    private static final BigDecimal DEFAULT_INTEREST_RATE      = new BigDecimal("20.00");

    private final StokvelConfigRepository stokvelConfigRepository;
    private final BorrowingConfigRepository borrowingConfigRepository;

    /** Returns the single stokvel config row, creating one with defaults if absent. */
    @Transactional
    public StokvelConfigEntity getOrCreate() {
        return stokvelConfigRepository.findAll().stream().findFirst()
                .orElseGet(() -> stokvelConfigRepository.save(new StokvelConfigEntity()));
    }

    public BigDecimal getTotalShares() {
        return stokvelConfigRepository.findAll().stream().findFirst()
                .map(StokvelConfigEntity::getTotalShares)
                .orElse(DEFAULT_TOTAL_SHARES);
    }

    public BigDecimal getSharePrice() {
        return stokvelConfigRepository.findAll().stream().findFirst()
                .map(StokvelConfigEntity::getSharePrice)
                .orElse(DEFAULT_SHARE_PRICE);
    }

    public BigDecimal getInterestRate() {
        return borrowingConfigRepository.findAll().stream().findFirst()
                .map(BorrowingConfigEntity::getInterestRate)
                .orElse(DEFAULT_INTEREST_RATE);
    }
}
