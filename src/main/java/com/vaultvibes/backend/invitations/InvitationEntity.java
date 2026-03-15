package com.vaultvibes.backend.invitations;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "invitations")
@Getter
@Setter
public class InvitationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "phone_number", nullable = false, length = 25)
    private String phoneNumber;

    @Column(name = "role", nullable = false, length = 40)
    private String role = "MEMBER";

    @Column(name = "invited_by")
    private UUID invitedBy;

    @Column(name = "share_units", nullable = false, precision = 19, scale = 4)
    private BigDecimal shareUnits;

    @Column(name = "price_per_unit", nullable = false, precision = 19, scale = 4)
    private BigDecimal pricePerUnit;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
