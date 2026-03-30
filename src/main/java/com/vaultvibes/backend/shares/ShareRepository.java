package com.vaultvibes.backend.shares;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public interface ShareRepository extends JpaRepository<ShareEntity, UUID> {

    List<ShareEntity> findByUserId(UUID userId);

    @Query("SELECT COALESCE(SUM(s.shareUnits), 0) FROM ShareEntity s WHERE s.user.id = :userId")
    BigDecimal sumShareUnitsByUserId(UUID userId);

    @Query("SELECT COALESCE(SUM(s.shareUnits), 0) FROM ShareEntity s")
    BigDecimal sumAllShareUnits();


    @Query("SELECT COALESCE(SUM(s.shareUnits * s.pricePerUnit), 0) FROM ShareEntity s WHERE s.user.id = :userId")
    BigDecimal sumCommitmentByUserId(UUID userId);

    @Query("SELECT COALESCE(AVG(s.pricePerUnit), 0) FROM ShareEntity s")
    BigDecimal avgPricePerUnit();

    @Query("SELECT COALESCE(SUM(s.shareUnits), 0) FROM ShareEntity s WHERE s.user.stokvelId = :stokvelId")
    BigDecimal sumAllShareUnitsByStokvelId(@Param("stokvelId") UUID stokvelId);

    @Query("SELECT COALESCE(AVG(s.pricePerUnit), 0) FROM ShareEntity s WHERE s.user.stokvelId = :stokvelId")
    BigDecimal avgPricePerUnitByStokvelId(@Param("stokvelId") UUID stokvelId);
}
