package com.vaultvibes.backend.contributions.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Contribution submission request.
 *
 * <p>The base contribution amount is derived server-side from the user's share units × share price.
 * {@code overrideAmount} allows submitting a different amount (e.g. when a loan was repaid
 * separately before the monthly contribution). If provided it must be positive and must not
 * exceed the member's total due (contribution + any active loan outstanding).
 *
 * <p>{@code proofS3Key} carries the S3 object key returned by S3UploadService — callers must
 * never construct this value themselves; it is always set by the controller after a successful
 * upload.</p>
 */
public record ContributionRequestDTO(
        @NotNull UUID userId,
        @NotNull LocalDate contributionDate,
        String notes,
        String proofS3Key,
        String proofFileType,
        @DecimalMin(value = "0.01", message = "overrideAmount must be greater than zero")
        BigDecimal overrideAmount
) {}
