package com.vaultvibes.backend.invitations;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvitationRepository extends JpaRepository<InvitationEntity, UUID> {

    List<InvitationEntity> findByPhoneNumber(String phoneNumber);

    Optional<InvitationEntity> findFirstByPhoneNumberAndStatusIn(String phoneNumber, List<String> statuses);

    @Query("SELECT COALESCE(SUM(i.shareUnits), 0) FROM InvitationEntity i WHERE i.status = 'PENDING'")
    BigDecimal sumPendingShareUnits();
}
