package com.vaultvibes.backend.contributions;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public interface ContributionRepository extends JpaRepository<ContributionEntity, UUID> {

    List<ContributionEntity> findByUserIdOrderByContributionDateDesc(UUID userId);

    List<ContributionEntity> findAllByOrderByContributionDateDesc();

    /** Only VERIFIED contributions count toward a member's paid-so-far total. */
    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM ContributionEntity c WHERE c.user.id = :userId AND c.verificationStatus = 'VERIFIED'")
    BigDecimal sumAmountByUserId(UUID userId);

    /** Only VERIFIED contributions count toward the pool balance cross-check. */
    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM ContributionEntity c WHERE c.verificationStatus = 'VERIFIED'")
    BigDecimal sumAllAmounts();

    /**
     * Returns the number of non-rejected contributions for a user in a given calendar month.
     * Uses the contribution_year_month(date) immutable function defined in V3 migration.
     * yearMonth format: YYYYMM (e.g. 202603 for March 2026).
     */
    @Query(value = "SELECT COUNT(*) FROM contributions WHERE user_id = :userId AND contribution_year_month(contribution_date) = :yearMonth AND verification_status <> 'REJECTED'", nativeQuery = true)
    long countByUserIdAndYearMonth(@Param("userId") UUID userId, @Param("yearMonth") int yearMonth);
}
