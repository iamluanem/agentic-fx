package com.agenticfx.app.security;

import com.agenticfx.app.config.AgenticFxProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TokenService {

    public record IssuedToken(String accessToken, Instant expiresAt, List<String> roles) {
    }

    private final JwtEncoder encoder;
    private final AgenticFxProperties.Security settings;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, AgenticFxProperties properties, Clock clock) {
        this.encoder = encoder;
        this.settings = properties.security();
        this.clock = clock;
    }

    public IssuedToken forUser(UUID userId, String displayName, List<String> roles) {
        return issue(userId, roles, settings.userTokenTtl(), Map.of("name", displayName));
    }

    /**
     * Token the agent-service uses on behalf of the user: same subject, AGENT role only, short-lived, and marked
     * with the actor ({@code act}) so every call is attributable to the agent in logs and audit.
     */
    public IssuedToken delegateToAgent(UUID userId) {
        return issue(userId, List.of("AGENT"), settings.agentTokenTtl(), Map.of("act", Map.of("sub", "agent-service")));
    }

    private IssuedToken issue(UUID subject, List<String> roles, Duration ttl, Map<String, Object> extra) {
        Instant now = clock.instant();
        Instant expires = now.plus(ttl);
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(settings.issuer())
                .subject(subject.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(expires)
                .claim("roles", roles);
        extra.forEach(claims::claim);
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                claims.build())).getTokenValue();
        return new IssuedToken(token, expires, roles);
    }
}
