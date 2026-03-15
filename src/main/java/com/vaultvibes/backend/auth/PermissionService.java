package com.vaultvibes.backend.auth;

import com.vaultvibes.backend.exception.UserForbiddenException;
import com.vaultvibes.backend.users.UserEntity;
import com.vaultvibes.backend.users.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Spring bean that enforces permission checks against the authenticated DB user.
 *
 * Usage in controllers:
 *   permissionService.require(Permission.ISSUE_LOAN);
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final UserService userService;

    /**
     * Throws UserForbiddenException (HTTP 403) if the current user does not hold
     * the requested permission.
     */
    public void require(Permission permission) {
        UserEntity user = userService.getCurrentUser();
        if (!RolePermissions.hasPermission(user.getRole(), permission)) {
            throw new UserForbiddenException(
                    "Role " + user.getRole() + " does not have permission: " + permission.name());
        }
    }
}
