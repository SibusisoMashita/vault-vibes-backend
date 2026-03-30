package com.vaultvibes.backend.config;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BorrowingConfigRepository extends JpaRepository<BorrowingConfigEntity, UUID> {

    Optional<BorrowingConfigEntity> findByStokvelId(UUID stokvelId);
}
