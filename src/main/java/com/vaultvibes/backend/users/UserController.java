package com.vaultvibes.backend.users;

import com.vaultvibes.backend.auth.Permission;
import com.vaultvibes.backend.auth.PermissionService;
import com.vaultvibes.backend.exception.UserForbiddenException;
import com.vaultvibes.backend.users.dto.MemberDTO;
import com.vaultvibes.backend.users.dto.UserDTO;
import com.vaultvibes.backend.users.UserEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "User management and profile operations")
public class UserController {

    private final UserService userService;
    private final PermissionService permissionService;

    @GetMapping("/me")
    @Operation(summary = "Get current authenticated user's profile and share data")
    public MemberDTO me() {
        UserEntity user = userService.getCurrentUser();
        return userService.toMemberDTO(user);
    }

    @GetMapping
    @Operation(summary = "List all users with their share and contribution summaries")
    public List<MemberDTO> listUsers() {
        return userService.listMembers();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one user's profile and share data",
               description = "Users may only view their own profile. TREASURER, CHAIRPERSON, and ADMIN may view any profile.")
    public MemberDTO getUser(@PathVariable UUID id) {
        UserEntity caller = userService.getCurrentUser();
        if (!caller.getId().equals(id) && !permissionService.currentUserHas(Permission.MANAGE_SHARES)) {
            throw new UserForbiddenException("Access denied — you may only view your own profile.");
        }
        return userService.getMember(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a user's full name, phone number, and email",
               description = "Users may only update their own profile. TREASURER, CHAIRPERSON, and ADMIN may update any profile.")
    public UserDTO update(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        UserEntity caller = userService.getCurrentUser();
        if (!caller.getId().equals(id) && !permissionService.currentUserHas(Permission.MANAGE_SHARES)) {
            throw new UserForbiddenException("Access denied — you may only update your own profile.");
        }
        return userService.updateProfile(id, body.get("fullName"), body.get("phoneNumber"), body.get("email"));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Change a user's status (ACTIVE, SUSPENDED)",
               description = "Admin-only operation. Accepts {\"status\": \"ACTIVE\"} or {\"status\": \"SUSPENDED\"}")
    public UserDTO updateStatus(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        permissionService.require(Permission.MANAGE_SHARES);
        String status = body.get("status");
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status field is required");
        }
        return userService.updateStatus(id, status.toUpperCase());
    }

    @PatchMapping("/me/onboarding-complete")
    @Operation(summary = "Mark the current user's onboarding wizard as completed",
               description = "Idempotent. Accepts {\"version\": 1}. Only updates the DB on the first call; re-launching the tour from Help does not call this endpoint.")
    public MemberDTO completeOnboarding(@RequestBody Map<String, Integer> body) {
        UserEntity user = userService.getCurrentUser();
        int version = body.getOrDefault("version", 1);
        return userService.completeOnboarding(user, version);
    }

    @PatchMapping("/{id}/role")
    @Operation(summary = "Change a user's role (MEMBER, TREASURER, CHAIRPERSON, ADMIN)",
               description = "Admin-only operation. Accepts {\"role\": \"MEMBER\"}")
    public UserDTO updateRole(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        permissionService.require(Permission.MANAGE_SHARES);
        String role = body.get("role");
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role field is required");
        }
        return userService.updateRole(id, role.toUpperCase());
    }
}
