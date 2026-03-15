package com.vaultvibes.backend.shares;

import com.vaultvibes.backend.config.StokvelConfigRepository;
import com.vaultvibes.backend.shares.dto.ShareDTO;
import com.vaultvibes.backend.shares.dto.ShareSummaryDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShareService {

    private static final BigDecimal DEFAULT_TOTAL_SHARES = new BigDecimal("240");
    private static final BigDecimal DEFAULT_SHARE_PRICE  = new BigDecimal("5000.00");

    private final ShareRepository shareRepository;
    private final StokvelConfigRepository stokvelConfigRepository;

    public List<ShareDTO> getSharesForUser(UUID userId) {
        return shareRepository.findByUserId(userId).stream()
                .map(this::toDTO)
                .toList();
    }

    public BigDecimal getTotalCommitmentForUser(UUID userId) {
        return shareRepository.sumCommitmentByUserId(userId);
    }

    public ShareSummaryDTO getSummary() {
        BigDecimal totalSharesCap = stokvelConfigRepository.findAll()
                .stream().findFirst()
                .map(c -> c.getTotalShares())
                .orElse(DEFAULT_TOTAL_SHARES);

        BigDecimal sharesSold     = shareRepository.sumAllShareUnits();
        BigDecimal sharesAvailable = totalSharesCap.subtract(sharesSold).max(BigDecimal.ZERO);
        BigDecimal pricePerShare  = shareRepository.avgPricePerUnit();
        if (pricePerShare.compareTo(BigDecimal.ZERO) == 0) {
            pricePerShare = stokvelConfigRepository.findAll()
                    .stream().findFirst()
                    .map(c -> c.getSharePrice())
                    .orElse(DEFAULT_SHARE_PRICE);
        }

        return new ShareSummaryDTO(totalSharesCap, sharesSold, sharesAvailable, pricePerShare);
    }

    public ShareDTO toDTO(ShareEntity share) {
        return new ShareDTO(
                share.getId(),
                share.getUser().getId(),
                share.getShareUnits(),
                share.getPricePerUnit(),
                share.getPurchasedAt()
        );
    }
}
