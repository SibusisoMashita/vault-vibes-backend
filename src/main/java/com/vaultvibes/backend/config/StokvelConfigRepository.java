package com.vaultvibes.backend.config;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface StokvelConfigRepository extends JpaRepository<StokvelConfigEntity, UUID> {

    Optional<StokvelConfigEntity> findByStokvelId(UUID stokvelId);
}
