package com.vaultvibes.backend.config;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "stokvel_config")
@Getter
@Setter
public class StokvelConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "total_shares", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalShares = new BigDecimal("240");

    @Column(name = "share_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal sharePrice = new BigDecimal("5000.00");

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
