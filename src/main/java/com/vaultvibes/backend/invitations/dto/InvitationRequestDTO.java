package com.vaultvibes.backend.invitations.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record InvitationRequestDTO(
        @NotBlank(message = "fullName is required")
        String fullName,

        @NotBlank(message = "phoneNumber is required")
        @Pattern(regexp = "^\\+[1-9]\\d{6,14}$", message = "phoneNumber must be in E.164 format")
        String phoneNumber,

        @NotBlank(message = "role is required")
        String role,

        @NotNull(message = "shareUnits is required")
        @Min(value = 1, message = "shareUnits must be at least 1")
        Integer shareUnits
) {}
