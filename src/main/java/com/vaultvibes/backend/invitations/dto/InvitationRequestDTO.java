package com.vaultvibes.backend.invitations.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record InvitationRequestDTO(
        @NotBlank(message = "phoneNumber is required")
        @Pattern(regexp = "^\\+[1-9]\\d{6,14}$", message = "phoneNumber must be in E.164 format")
        String phoneNumber,

        @NotBlank(message = "role is required")
        String role,

        @NotNull(message = "shareUnits is required")
        @DecimalMin(value = "0.0001", message = "A member must be assigned at least part of a share.")
        BigDecimal shareUnits
) {}
