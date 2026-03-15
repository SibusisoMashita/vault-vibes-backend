package com.vaultvibes.backend.contributions.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Contribution submission request.
 *
 * <p>The contribution amount is derived server-side from the user's share units × share price.
 * {@code proofS3Key} carries the S3 object key returned by S3UploadService — callers must
 * never construct this value themselves; it is always set by the controller after a successful
 * upload.</p>
 */
public record ContributionRequestDTO(
        @NotNull UUID userId,
        @NotNull LocalDate contributionDate,
        String notes,
        String proofS3Key,
        String proofFileType
) {}
