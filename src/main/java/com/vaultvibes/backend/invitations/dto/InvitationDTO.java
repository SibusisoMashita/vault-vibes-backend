package com.vaultvibes.backend.invitations.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record InvitationDTO(
        UUID id,
        String phoneNumber,
        String role,
        UUID invitedBy,
        BigDecimal shareUnits,
        BigDecimal pricePerUnit,
        String status,
        OffsetDateTime createdAt
) {}
