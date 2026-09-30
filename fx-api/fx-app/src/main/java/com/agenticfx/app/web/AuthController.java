package com.agenticfx.app.web;

import com.agenticfx.app.security.CurrentUser;
import com.agenticfx.app.security.TokenService;
import com.agenticfx.app.security.UserAccounts;
import com.agenticfx.app.web.dto.Requests;
import com.agenticfx.app.web.dto.Views;
import com.agenticfx.core.shared.NotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final UserAccounts users;
    private final TokenService tokens;

    public AuthController(UserAccounts users, TokenService tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    /** Password login for the demo. Production would delegate to an identity provider (OIDC). */
    @PostMapping("/auth/token")
    public ResponseEntity<Views.TokenView> token(@Valid @RequestBody Requests.Login request) {
        return users.authenticate(request.email(), request.password())
                .map(u -> {
                    var t = tokens.forUser(u.id(), u.displayName(), u.roles());
                    return ResponseEntity.ok(new Views.TokenView(t.accessToken(), "Bearer", t.expiresAt(), t.roles()));
                })
                .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    /** Issues the short-lived, AGENT-only token the agent-service uses on the caller's behalf. */
    @PostMapping("/auth/delegate")
    public Views.TokenView delegate(@AuthenticationPrincipal Jwt jwt) {
        var t = tokens.delegateToAgent(CurrentUser.id(jwt));
        return new Views.TokenView(t.accessToken(), "Bearer", t.expiresAt(), t.roles());
    }

    @GetMapping("/me")
    public Views.MeView me(@AuthenticationPrincipal Jwt jwt) {
        var u = users.find(CurrentUser.id(jwt)).orElseThrow(() -> new NotFoundException("User", jwt.getSubject()));
        return new Views.MeView(u.id(), u.email(), u.displayName(), u.roles());
    }
}
