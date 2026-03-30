package com.vaultvibes.backend.users.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record MemberDTO(
        UUID id,
        String fullName,
        String phoneNumber,
        String email,
        String role,
        String status,
        UUID stokvelId,
        BigDecimal sharesOwned,
        BigDecimal totalCommitment,
        BigDecimal paidSoFar,
        BigDecimal remaining,
        boolean onboardingCompleted,
        int onboardingVersion
) {}
