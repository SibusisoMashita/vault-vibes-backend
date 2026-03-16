package com.vaultvibes.backend.auth;

import com.vaultvibes.backend.exception.UserNotActiveException;
import com.vaultvibes.backend.exception.UserNotRegisteredException;
import com.vaultvibes.backend.users.UserEntity;
import com.vaultvibes.backend.users.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Resolves the authenticated DB user once per request and stores it in UserContextHolder.
 *
 * Runs after Spring Security has validated the JWT. If the SecurityContext has no
 * authenticated principal (public endpoints), the interceptor is a no-op.
 *
 * On every authenticated request:
 *  1. Calls UserService.getCurrentUser() — handles first-login activation.
 *  2. Stores the result in UserContextHolder for all downstream service calls.
 *  3. Clears the holder in afterCompletion to prevent thread-pool leaks.
 *
 * UserNotRegisteredException and UserNotActiveException propagate to GlobalExceptionHandler.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserContextInterceptor implements HandlerInterceptor {

    private final CurrentUserService currentUserService;
    private final UserService        userService;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {

        String cognitoId = currentUserService.getCurrentUserId();
        if (cognitoId == null) {
            // No JWT present — public endpoint, pass through
            return true;
        }

        UserEntity user = userService.getCurrentUser();
        UserContextHolder.set(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        UserContextHolder.clear();
    }
}
