package com.vaultvibes.backend.distributions;

import com.vaultvibes.backend.auth.Permission;
import com.vaultvibes.backend.auth.PermissionService;
import com.vaultvibes.backend.distributions.dto.DistributionDTO;
import com.vaultvibes.backend.distributions.dto.DistributionRequestDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/distributions")
@RequiredArgsConstructor
public class DistributionController {

    private final DistributionService distributionService;
    private final PermissionService permissionService;

    @GetMapping
    public List<DistributionDTO> list() {
        return distributionService.listAll();
    }

    /**
     * Records a distribution payout for a member and fires a WhatsApp notification.
     * Requires MANAGE_SHARES — restricted to TREASURER, CHAIRPERSON, and ADMIN.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DistributionDTO execute(@Valid @RequestBody DistributionRequestDTO request) {
        permissionService.require(Permission.MANAGE_SHARES);
        return distributionService.execute(
                request.userId(),
                request.amount(),
                request.periodStart(),
                request.periodEnd());
    }
}

