package com.vaultvibes.backend.shares;

import com.vaultvibes.backend.users.UserEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "shares")
@Getter
@Setter
public class ShareEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(name = "share_units", nullable = false, precision = 19, scale = 4)
    private BigDecimal shareUnits = BigDecimal.ZERO;

    @Column(name = "price_per_unit", nullable = false, precision = 19, scale = 4)
    private BigDecimal pricePerUnit = BigDecimal.ZERO;

    @Column(name = "purchased_at", nullable = false)
    private OffsetDateTime purchasedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
