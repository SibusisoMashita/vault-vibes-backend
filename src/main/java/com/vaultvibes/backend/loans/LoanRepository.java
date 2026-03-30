package com.vaultvibes.backend.loans;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public interface LoanRepository extends JpaRepository<LoanEntity, UUID> {

    List<LoanEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<LoanEntity> findAllByOrderByCreatedAtDesc();

    List<LoanEntity> findByStatusIn(List<String> statuses);

    /**
     * Outstanding loan balance = money currently lent out that still belongs to the pool.
     * Uses principal - amount_repaid to correctly handle partial repayments.
     */
    @Query("SELECT COALESCE(SUM(l.principalAmount - l.amountRepaid), 0) FROM LoanEntity l WHERE l.status IN ('ACTIVE', 'APPROVED')")
    BigDecimal sumOutstandingLoansBalance();

    @Query("SELECT COUNT(l) FROM LoanEntity l WHERE l.status IN ('ACTIVE', 'APPROVED')")
    long countActiveLoans();

    /**
     * Outstanding loan balance for a single user — used to deduct from their personal borrow limit.
     */
    @Query("SELECT COALESCE(SUM(l.principalAmount - l.amountRepaid), 0) FROM LoanEntity l " +
           "WHERE l.user.id = :userId AND l.status IN ('ACTIVE', 'APPROVED')")
    BigDecimal sumOutstandingLoansByUserId(@Param("userId") UUID userId);

    /**
     * Cross-month restriction: counts ACTIVE loans for this user whose issued_at falls in a
     * calendar month strictly before the current month. Returns > 0 means the user is blocked.
     */
    @Query(value = "SELECT COUNT(*) FROM loans " +
                   "WHERE user_id = :userId " +
                   "  AND status = 'ACTIVE' " +
                   "  AND issued_at IS NOT NULL " +
                   "  AND DATE_TRUNC('month', issued_at) < DATE_TRUNC('month', NOW())",
           nativeQuery = true)
    long countCrossMonthActiveLoans(@Param("userId") UUID userId);

    @Query("SELECT COALESCE(SUM(l.principalAmount - l.amountRepaid), 0) FROM LoanEntity l " +
           "WHERE l.status IN ('ACTIVE', 'APPROVED') AND l.user.stokvelId = :stokvelId")
    BigDecimal sumOutstandingLoansBalanceByStokvelId(@Param("stokvelId") UUID stokvelId);

    @Query("SELECT COUNT(l) FROM LoanEntity l WHERE l.status IN ('ACTIVE', 'APPROVED') AND l.user.stokvelId = :stokvelId")
    long countActiveLoansByStokvelId(@Param("stokvelId") UUID stokvelId);

    @Query("SELECT l FROM LoanEntity l WHERE l.user.stokvelId = :stokvelId ORDER BY l.createdAt DESC")
    List<LoanEntity> findByStokvelIdOrderByCreatedAtDesc(@Param("stokvelId") UUID stokvelId);

    @Query("SELECT l FROM LoanEntity l WHERE l.status IN :statuses AND l.user.stokvelId = :stokvelId")
    List<LoanEntity> findByStatusInAndStokvelId(@Param("statuses") List<String> statuses,
                                                @Param("stokvelId") UUID stokvelId);
}
