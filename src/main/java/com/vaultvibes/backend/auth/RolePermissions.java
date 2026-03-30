package com.vaultvibes.backend.auth;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for which permissions each DB role holds.
 *
 * Role strings match the canonical DB values (case-insensitive comparison used in
 * hasPermission so "treasurer" and "TREASURER" both work).
 */
public final class RolePermissions {

    private static final Set<Permission> MEMBER_PERMISSIONS = EnumSet.of(
            Permission.VIEW_POOL,
            Permission.VIEW_LEDGER,
            Permission.CONTRIBUTE,
            Permission.REQUEST_LOAN
    );

    private static final Set<Permission> GROUP_ADMIN_PERMISSIONS = EnumSet.of(
            Permission.VIEW_POOL,
            Permission.VIEW_LEDGER,
            Permission.CONTRIBUTE,
            Permission.REQUEST_LOAN,
            Permission.VERIFY_CONTRIBUTION,
            Permission.ISSUE_LOAN,
            Permission.RECORD_REPAYMENT,
            Permission.RECORD_BANK_INTEREST,
            Permission.INVITE_MEMBER,
            Permission.MANAGE_SHARES,
            Permission.VIEW_FINANCIAL_REPORTS,
            Permission.AUDIT_LEDGER
    );

    private static final Set<Permission> PLATFORM_ADMIN_PERMISSIONS = EnumSet.of(
            Permission.VIEW_POOL,
            Permission.VIEW_LEDGER,
            Permission.CONTRIBUTE,
            Permission.REQUEST_LOAN,
            Permission.VERIFY_CONTRIBUTION,
            Permission.ISSUE_LOAN,
            Permission.RECORD_REPAYMENT,
            Permission.RECORD_BANK_INTEREST,
            Permission.INVITE_MEMBER,
            Permission.MANAGE_SHARES,
            Permission.VIEW_FINANCIAL_REPORTS,
            Permission.AUDIT_LEDGER,
            Permission.MANAGE_STOKVELS,
            Permission.SYSTEM_ADMIN
    );

    private static final Set<Permission> ALL_PERMISSIONS = EnumSet.allOf(Permission.class);

    private static final Map<String, Set<Permission>> ROLE_MAP = Map.of(
            "MEMBER",      MEMBER_PERMISSIONS,
            "TREASURER",   GROUP_ADMIN_PERMISSIONS,
            "CHAIRPERSON", GROUP_ADMIN_PERMISSIONS,
            "ADMIN",       PLATFORM_ADMIN_PERMISSIONS
    );

    private RolePermissions() {}

    /**
     * Returns true if the given role has the requested permission.
     * Role comparison is case-insensitive. ADMIN implicitly passes all checks.
     */
    public static boolean hasPermission(String role, Permission permission) {
        if (role == null) return false;
        String r = role.toUpperCase();
        if ("ADMIN".equals(r)) return true;
        Set<Permission> perms = ROLE_MAP.get(r);
        return perms != null && perms.contains(permission);
    }

    /**
     * Returns true for roles that act as group administrators.
     */
    public static boolean isGroupAdmin(String role) {
        if (role == null) return false;
        String r = role.toUpperCase();
        return "TREASURER".equals(r) || "CHAIRPERSON".equals(r) || "ADMIN".equals(r);
    }
}
