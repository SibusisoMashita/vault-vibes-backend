package com.vaultvibes.backend.auth;

/**
 * All named permissions in the Vault Vibes system.
 *
 * These mirror the frontend Permission constants in src/auth/permissions.ts.
 * ROLE_PERMISSIONS in RolePermissions.java is the authoritative mapping from
 * DB role strings (MEMBER, TREASURER, CHAIRPERSON, ADMIN) to these values.
 */
public enum Permission {

    // ── Member-level ──────────────────────────────────────────────────────────
    VIEW_POOL,
    VIEW_LEDGER,
    CONTRIBUTE,
    REQUEST_LOAN,

    // ── Group administrator (Treasurer / Chairperson) ────────────────────────
    VERIFY_CONTRIBUTION,
    ISSUE_LOAN,
    RECORD_REPAYMENT,
    RECORD_BANK_INTEREST,
    INVITE_MEMBER,
    MANAGE_SHARES,
    VIEW_FINANCIAL_REPORTS,
    AUDIT_LEDGER,

    // ── System administration (future) ────────────────────────────────────────
    SYSTEM_ADMIN,
}
