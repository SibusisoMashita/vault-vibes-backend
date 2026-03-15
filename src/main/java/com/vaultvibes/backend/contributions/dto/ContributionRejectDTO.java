package com.vaultvibes.backend.contributions.dto;

import jakarta.validation.constraints.NotBlank;

public record ContributionRejectDTO(
        @NotBlank String reason
) {}
