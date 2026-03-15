package com.vaultvibes.backend.users.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserDTO(
        UUID id,
        String fullName,
        String phoneNumber,
        String email,
        String status,
        String role,
        OffsetDateTime createdAt
) {}
