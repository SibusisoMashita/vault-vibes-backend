package com.vaultvibes.backend.stokvels.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record StokvelDTO(
        UUID id,
        String name,
        String description,
        String status,
        OffsetDateTime createdAt
) {}
