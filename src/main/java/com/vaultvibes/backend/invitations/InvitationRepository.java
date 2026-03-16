package com.vaultvibes.backend.invitations;

import com.vaultvibes.backend.users.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvitationRepository extends JpaRepository<InvitationEntity, UUID> {

    Optional<InvitationEntity> findFirstByUserAndStatusIn(UserEntity user, List<String> statuses);
}
