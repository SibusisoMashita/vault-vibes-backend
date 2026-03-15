package com.vaultvibes.backend.shares.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ShareDTO(
        UUID id,
        UUID userId,
        BigDecimal shareUnits,
        BigDecimal pricePerUnit,
        OffsetDateTime purchasedAt
) {}
