package com.vaultvibes.backend.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Provides access to the currently authenticated user from the Cognito JWT.
 * Use this in services to scope queries to the authenticated user.
 */
@Service
@Slf4j
public class CurrentUserService {

    /**
     * Returns the Cognito sub (user UUID) from the JWT, or null if not authenticated.
     */
    public String getCurrentUserId() {
        Jwt jwt = extractJwt();
        return jwt != null ? jwt.getSubject() : null;
    }

    /**
     * Returns the phone_number claim from the JWT if present.
     * Not always included in Cognito access tokens — use getCurrentUsername() as fallback.
     */
    public String getCurrentPhoneNumber() {
        Jwt jwt = extractJwt();
        return jwt != null ? jwt.getClaimAsString("phone_number") : null;
    }

    /**
     * Returns the Cognito username claim from the JWT.
     * Always present in Cognito access tokens.
     */
    public String getCurrentUsername() {
        Jwt jwt = extractJwt();
        return jwt != null ? jwt.getClaimAsString("username") : null;
    }

    /**
     * Returns the email claim from the JWT.
     */
    public String getCurrentEmail() {
        Jwt jwt = extractJwt();
        return jwt != null ? jwt.getClaimAsString("email") : null;
    }

    /**
     * Builds a full AuthenticatedUser from the JWT claims.
     * Returns null if the request is not authenticated (e.g. during early development).
     */
    public AuthenticatedUser getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            List<String> roles = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .toList();

            return new AuthenticatedUser(
                    jwt.getSubject(),
                    jwt.getClaimAsString("email"),
                    jwt.getClaimAsString("phone_number"),
                    roles
            );
        }
        return null;
    }

    private Jwt extractJwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken();
        }
        return null;
    }
}
