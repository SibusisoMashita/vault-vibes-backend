package com.vaultvibes.backend.users;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByCognitoId(String cognitoId);

    Optional<UserEntity> findByPhoneNumber(String phoneNumber);

    /**
     * Used during first-login only. The pessimistic write lock serialises concurrent
     * logins for the same phone number, preventing the deadlock that arises when the
     * frontend fires multiple simultaneous requests before the account is activated.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<UserEntity> findWithLockByPhoneNumber(String phoneNumber);
}
