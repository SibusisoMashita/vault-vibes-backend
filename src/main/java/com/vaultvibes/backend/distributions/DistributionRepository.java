package com.vaultvibes.backend.distributions;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DistributionRepository extends JpaRepository<DistributionEntity, UUID> {

    List<DistributionEntity> findByUserIdOrderByDistributedAtDesc(UUID userId);

    List<DistributionEntity> findAllByOrderByDistributedAtDesc();

    @Query("SELECT d FROM DistributionEntity d WHERE d.user.stokvelId = :stokvelId ORDER BY d.distributedAt DESC")
    List<DistributionEntity> findByStokvelIdOrderByDistributedAtDesc(@Param("stokvelId") UUID stokvelId);
}
