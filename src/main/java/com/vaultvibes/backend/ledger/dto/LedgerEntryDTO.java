package com.vaultvibes.backend.ledger.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record LedgerEntryDTO(
        UUID id,
        UUID userId,
        String memberName,
        String entryType,
        BigDecimal amount,
        String reference,
        String description,
        OffsetDateTime postedAt
) {}
