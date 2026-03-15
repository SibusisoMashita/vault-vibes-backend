package com.vaultvibes.backend.contributions.dto;

import java.util.UUID;

/**
 * Internal projection used by ContributionService to pass proof metadata to the
 * controller for access-control checks and signed URL generation.
 * Not serialised to API responses.
 */
public record ContributionProofInfoDTO(
        UUID ownerId,
        String proofS3Key,
        String proofFileType
) {}
