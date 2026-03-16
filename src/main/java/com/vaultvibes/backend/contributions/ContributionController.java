package com.vaultvibes.backend.contributions;

import com.vaultvibes.backend.auth.Permission;
import com.vaultvibes.backend.auth.PermissionService;
import com.vaultvibes.backend.config.ProofSignedUrlService;
import com.vaultvibes.backend.config.S3UploadService;
import com.vaultvibes.backend.exception.UserForbiddenException;
import com.vaultvibes.backend.contributions.dto.ContributionDTO;
import com.vaultvibes.backend.contributions.dto.ContributionPreviewDTO;
import com.vaultvibes.backend.contributions.dto.ContributionProofInfoDTO;
import com.vaultvibes.backend.contributions.dto.ContributionRejectDTO;
import com.vaultvibes.backend.contributions.dto.ContributionRequestDTO;
import com.vaultvibes.backend.users.UserEntity;
import com.vaultvibes.backend.users.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/contributions")
@RequiredArgsConstructor
@Tag(name = "Contributions", description = "Member payment contributions to the pool")
public class ContributionController {

    private final ContributionService contributionService;
    private final PermissionService permissionService;
    private final S3UploadService s3UploadService;
    private final ProofSignedUrlService proofSignedUrlService;
    private final UserService userService;

    @GetMapping
    @Operation(summary = "List contributions — admins see all members, members see only their own")
    @ApiResponse(responseCode = "200", description = "Contribution list returned")
    public List<ContributionDTO> list() {
        if (permissionService.currentUserHas(Permission.VERIFY_CONTRIBUTION)) {
            return contributionService.listAll();
        }
        UserEntity currentUser = userService.getCurrentUser();
        return contributionService.listForUser(currentUser.getId());
    }

    @GetMapping("/preview/{userId}")
    @Operation(summary = "Get payment breakdown for a user's next contribution",
               description = "Returns contribution amount (shares × share price), active loan outstanding, and total due. Does not create any records. " +
                       "Members may only preview their own data. Admins may preview any member.")
    @ApiResponse(responseCode = "403", description = "Not authorised to preview this user's data")
    public ContributionPreviewDTO preview(@PathVariable UUID userId) {
        UserEntity caller = userService.getCurrentUser();
        if (!caller.getId().equals(userId) && !permissionService.currentUserHas(Permission.VERIFY_CONTRIBUTION)) {
            throw new UserForbiddenException("Access denied — you may only preview your own contribution data.");
        }
        return contributionService.getPreview(userId);
    }

    // -----------------------------------------------------------------------
    // POST — JSON body (no file, no proof)
    // -----------------------------------------------------------------------

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a monthly contribution without proof (admin only)",
               description = "Admin-only endpoint for recording cash or EFT contributions that do not " +
                       "require a proof-of-payment file. Contribution is auto-verified immediately. " +
                       "Requires TREASURER or CHAIRPERSON role. " +
                       "Members must use the multipart/form-data endpoint and upload proof.")
    @ApiResponse(responseCode = "201", description = "Contribution recorded")
    @ApiResponse(responseCode = "400", description = "Validation error or user has no shares")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions — admin role required")
    public ContributionDTO create(@Valid @RequestBody ContributionRequestDTO request) {
        permissionService.require(Permission.VERIFY_CONTRIBUTION);
        return contributionService.addContribution(request);
    }

    // -----------------------------------------------------------------------
    // POST — multipart/form-data (with proof file)
    // -----------------------------------------------------------------------

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a monthly contribution with proof of payment",
               description = "Accepts multipart/form-data. An optional proof-of-payment file " +
                       "(PDF, JPG, JPEG or PNG, max 5 MB) is uploaded to private S3 storage. " +
                       "Contributions with proof start in PENDING status until an admin verifies. " +
                       "Members may only submit contributions for themselves. Admins may submit for any member.")
    @ApiResponse(responseCode = "201", description = "Contribution recorded")
    @ApiResponse(responseCode = "400", description = "Invalid file type, file too large, or validation error")
    @ApiResponse(responseCode = "403", description = "Not authorised to submit on behalf of another member")
    public ContributionDTO createWithProof(
            @RequestParam UUID userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate contributionDate,
            @RequestParam(required = false) String notes,
            @RequestPart(name = "proofFile", required = false) MultipartFile proofFile
    ) {
        UserEntity caller = userService.getCurrentUser();
        if (!caller.getId().equals(userId) && !permissionService.currentUserHas(Permission.VERIFY_CONTRIBUTION)) {
            throw new UserForbiddenException("You may only submit contributions for yourself.");
        }

        String proofS3Key    = null;
        String proofFileType = null;

        if (proofFile != null && !proofFile.isEmpty()) {
            try {
                proofS3Key    = s3UploadService.upload(userId, proofFile);
                proofFileType = s3UploadService.detectFileType(proofFile);
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not read uploaded file: " + e.getMessage());
            }
        }

        ContributionRequestDTO request = new ContributionRequestDTO(
                userId, contributionDate, notes, proofS3Key, proofFileType);

        return contributionService.addContribution(request);
    }

    // -----------------------------------------------------------------------
    // GET /{id}/proof — generate a signed URL for the proof file
    // -----------------------------------------------------------------------

    @GetMapping("/{id}/proof")
    @Operation(summary = "Get a signed URL for a contribution's proof of payment",
               description = "Access is restricted: members may only retrieve their own proof. " +
                       "Administrators (TREASURER, CHAIRPERSON, ADMIN) may access any proof. " +
                       "The returned URL expires in " + ProofSignedUrlService.EXPIRY_MINUTES + " minutes. " +
                       "PDF proofs include Content-Disposition: attachment to force a download.")
    @ApiResponse(responseCode = "200", description = "Signed URL returned")
    @ApiResponse(responseCode = "400", description = "No proof on file for this contribution")
    @ApiResponse(responseCode = "403", description = "Not authorised to access this proof")
    public Map<String, String> getProofUrl(@PathVariable UUID id) {
        UserEntity caller = userService.getCurrentUser();
        ContributionProofInfoDTO info = contributionService.getProofInfo(id);

        // Owner can always access their own proof; others need VERIFY_CONTRIBUTION
        if (!info.ownerId().equals(caller.getId())) {
            permissionService.require(Permission.VERIFY_CONTRIBUTION);
        }

        if (info.proofS3Key() == null || info.proofS3Key().isBlank()) {
            throw new IllegalArgumentException("No proof of payment on file for this contribution.");
        }

        String url = proofSignedUrlService.generateSignedUrl(info.proofS3Key(), info.proofFileType());
        return Map.of("url", url);
    }

    // -----------------------------------------------------------------------
    // POST /{id}/verify — mark as VERIFIED
    // -----------------------------------------------------------------------

    @PostMapping("/{id}/verify")
    @Operation(summary = "Verify a contribution proof",
               description = "Marks the contribution as VERIFIED. Requires TREASURER or CHAIRPERSON role.")
    @ApiResponse(responseCode = "200", description = "Contribution verified")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions")
    public ContributionDTO verify(@PathVariable UUID id) {
        permissionService.require(Permission.VERIFY_CONTRIBUTION);
        return contributionService.verify(id);
    }

    // -----------------------------------------------------------------------
    // POST /{id}/reject — mark as REJECTED with a reason
    // -----------------------------------------------------------------------

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject a contribution proof",
               description = "Marks the contribution as REJECTED with a mandatory reason. Requires TREASURER or CHAIRPERSON role.")
    @ApiResponse(responseCode = "200", description = "Contribution rejected")
    @ApiResponse(responseCode = "400", description = "Reason is required")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions")
    public ContributionDTO reject(@PathVariable UUID id, @Valid @RequestBody ContributionRejectDTO body) {
        permissionService.require(Permission.VERIFY_CONTRIBUTION);
        return contributionService.reject(id, body.reason());
    }
}
