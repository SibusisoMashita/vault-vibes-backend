package com.vaultvibes.backend.invitations.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record InvitationDTO(
        UUID id,
        UUID userId,
        String userFullName,
        String userPhoneNumber,
        String userRole,
        UUID invitedBy,
        String status,
        OffsetDateTime resentAt,
        OffsetDateTime createdAt
) {}
