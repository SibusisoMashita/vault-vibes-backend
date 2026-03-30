package com.vaultvibes.backend.stokvels;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StokvelsRepository extends JpaRepository<StokvelsEntity, UUID> {

    List<StokvelsEntity> findAllByOrderByCreatedAtAsc();

    Optional<StokvelsEntity> findByName(String name);

    List<StokvelsEntity> findByStatus(String status);
}
