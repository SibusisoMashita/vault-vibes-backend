package com.vaultvibes.backend.auth;

import com.vaultvibes.backend.users.UserEntity;

/**
 * Thread-local store for the resolved DB user of the current request.
 *
 * Populated once per request by UserContextInterceptor so that all downstream
 * service calls share the same user without redundant database lookups.
 * Always cleared in afterCompletion to prevent leaks across pooled threads.
 */
public final class UserContextHolder {

    private static final ThreadLocal<UserEntity> CONTEXT = new ThreadLocal<>();

    private UserContextHolder() {}

    public static void set(UserEntity user) {
        CONTEXT.set(user);
    }

    public static UserEntity get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
