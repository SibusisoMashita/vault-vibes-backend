package com.vaultvibes.backend.shares;

import com.vaultvibes.backend.config.StokvelConfigService;
import com.vaultvibes.backend.shares.dto.ShareDTO;
import com.vaultvibes.backend.shares.dto.ShareSummaryDTO;
import com.vaultvibes.backend.users.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShareService {

    private final ShareRepository shareRepository;
    private final StokvelConfigService configService;

    public List<ShareDTO> getSharesForUser(UUID userId) {
        return shareRepository.findByUserId(userId).stream()
                .map(this::toDTO)
                .toList();
    }

    public BigDecimal getTotalCommitmentForUser(UUID userId) {
        return shareRepository.sumCommitmentByUserId(userId);
    }

    public ShareSummaryDTO getSummary() {
        BigDecimal totalSharesCap  = configService.getTotalShares();
        BigDecimal sharesSold      = shareRepository.sumAllShareUnits();
        BigDecimal sharesAvailable = totalSharesCap.subtract(sharesSold).max(BigDecimal.ZERO);
        BigDecimal pricePerShare   = resolveEffectiveSharePrice();

        return new ShareSummaryDTO(totalSharesCap, sharesSold, sharesAvailable, pricePerShare);
    }

    /**
     * Creates a share record for a newly invited user.
     * Called by InvitationService at invite time.
     */
    @Transactional
    public ShareDTO allocateShares(UserEntity user, int shareUnits) {
        BigDecimal price = configService.getSharePrice();
        ShareEntity share = new ShareEntity();
        share.setUser(user);
        share.setShareUnits(new BigDecimal(shareUnits));
        share.setPricePerUnit(price);
        share.setPurchasedAt(OffsetDateTime.now());
        return toDTO(shareRepository.save(share));
    }

    /**
     * Updates the total share units for a user.
     * If the user has an existing share record, its units are replaced.
     * If not, a new record is created at the current share price.
     */
    @Transactional
    public ShareDTO updateShares(UUID userId, int shareUnits, UserEntity user) {
        List<ShareEntity> existing = shareRepository.findByUserId(userId);
        ShareEntity share;
        if (!existing.isEmpty()) {
            share = existing.get(0);
            share.setShareUnits(new BigDecimal(shareUnits));
        } else {
            share = new ShareEntity();
            share.setUser(user);
            share.setShareUnits(new BigDecimal(shareUnits));
            share.setPricePerUnit(configService.getSharePrice());
            share.setPurchasedAt(OffsetDateTime.now());
        }
        return toDTO(shareRepository.save(share));
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

    private BigDecimal resolveEffectiveSharePrice() {
        BigDecimal avg = shareRepository.avgPricePerUnit();
        return avg.compareTo(BigDecimal.ZERO) == 0 ? configService.getSharePrice() : avg;
    }
}
