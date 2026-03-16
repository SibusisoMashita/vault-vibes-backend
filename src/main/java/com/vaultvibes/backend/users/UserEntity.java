package com.vaultvibes.backend.users;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "cognito_id", unique = true, length = 255)
    private String cognitoId;

    @Column(name = "phone_number", nullable = false, unique = true, length = 25)
    private String phoneNumber;

    @Column(name = "email", unique = true, length = 255)
    private String email;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @Column(name = "status", nullable = false, length = 40)
    private String status = "PENDING";

    @Column(name = "role", nullable = false, length = 40)
    private String role = "MEMBER";

    @Column(name = "onboarding_completed", nullable = false)
    private boolean onboardingCompleted = false;

    @Column(name = "onboarding_completed_at")
    private OffsetDateTime onboardingCompletedAt;

    @Column(name = "onboarding_version", nullable = false)
    private int onboardingVersion = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
