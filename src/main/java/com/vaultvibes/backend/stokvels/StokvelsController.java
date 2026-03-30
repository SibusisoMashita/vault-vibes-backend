package com.vaultvibes.backend.stokvels;

import com.vaultvibes.backend.auth.Permission;
import com.vaultvibes.backend.auth.PermissionService;
import com.vaultvibes.backend.stokvels.dto.CreateStokvelDTO;
import com.vaultvibes.backend.stokvels.dto.StokvelDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/stokvels")
@RequiredArgsConstructor
@Tag(name = "Stokvels", description = "Platform-level stokvel management — ADMIN only")
public class StokvelsController {

    private final StokvelsService stokvelsService;
    private final PermissionService permissionService;

    @GetMapping
    @Operation(summary = "List all stokvels on the platform")
    public List<StokvelDTO> listAll() {
        permissionService.require(Permission.MANAGE_STOKVELS);
        return stokvelsService.listAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single stokvel by ID")
    public StokvelDTO getById(@PathVariable UUID id) {
        permissionService.require(Permission.MANAGE_STOKVELS);
        return stokvelsService.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new stokvel group")
    public StokvelDTO create(@Valid @RequestBody CreateStokvelDTO request) {
        permissionService.require(Permission.MANAGE_STOKVELS);
        return stokvelsService.create(request);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a stokvel's name and description")
    public StokvelDTO update(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        permissionService.require(Permission.MANAGE_STOKVELS);
        return stokvelsService.update(id, body.get("name"), body.get("description"));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Activate or deactivate a stokvel")
    public StokvelDTO setStatus(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        permissionService.require(Permission.MANAGE_STOKVELS);
        String status = body.get("status");
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status field is required");
        }
        return stokvelsService.setStatus(id, status);
    }
}
