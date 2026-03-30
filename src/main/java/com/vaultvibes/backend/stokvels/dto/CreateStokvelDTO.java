package com.vaultvibes.backend.stokvels.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateStokvelDTO(
        @NotBlank @Size(max = 100) String name,
        String description
) {}
