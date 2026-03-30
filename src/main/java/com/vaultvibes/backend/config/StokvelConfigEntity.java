package com.vaultvibes.backend.config;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    @Column(name = "cycle_months", nullable = false)
    private int cycleMonths = 12;

    /** Months per year when contributions are expected (≤ cycleMonths). Set to 11 to skip December. */
    @Column(name = "contribution_months", nullable = false)
    private int contributionMonths = 12;

    @Column(name = "monthly_contribution", nullable = false, precision = 19, scale = 2)
    private BigDecimal monthlyContribution = new BigDecimal("500.00");

    @Column(name = "cycle_start_date", nullable = false)
    private LocalDate cycleStartDate = LocalDate.of(2025, 1, 1);

    @Column(name = "stokvel_id")
    private UUID stokvelId;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
