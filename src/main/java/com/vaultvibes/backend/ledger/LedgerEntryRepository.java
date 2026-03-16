package com.vaultvibes.backend.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, UUID> {

    List<LedgerEntryEntity> findByUserIdOrderByPostedAtDesc(UUID userId);

    List<LedgerEntryEntity> findAllByOrderByPostedAtDesc();

    /**
     * Bank interest received on or after the given date — used to compute the
     * rolling average for bank interest projections.
     */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntryEntity e WHERE e.entryType = 'BANK_INTEREST' AND e.postedAt >= :since")
    BigDecimal sumBankInterestSince(@org.springframework.data.repository.query.Param("since") java.time.OffsetDateTime since);

    /**
     * Bank balance = SUM of all signed ledger amounts.
     * Positive entries (CONTRIBUTION, LOAN_REPAYMENT, BANK_INTEREST) increase the balance.
     * Negative entries (LOAN_ISSUED) decrease the balance.
     */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntryEntity e")
    BigDecimal sumAllLedgerAmounts();
}
