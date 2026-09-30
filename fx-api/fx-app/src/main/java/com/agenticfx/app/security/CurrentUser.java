package com.agenticfx.app.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Access to the authenticated principal. The subject is always the account owner's user id. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /** "agent" when the call comes through a delegated agent token, otherwise "user". */
    public static String channel(Jwt jwt) {
        Object act = jwt.getClaims().get("act");
        return act instanceof Map<?, ?> ? "agent" : "user";
    }

    public static Optional<String> subjectFromContext() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof JwtAuthenticationToken token ? Optional.of(token.getToken().getSubject())
                : Optional.empty();
    }
}
