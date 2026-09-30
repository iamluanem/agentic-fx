package com.agenticfx.app.web;

import com.agenticfx.app.security.CurrentUser;
import com.agenticfx.app.web.dto.Requests;
import com.agenticfx.app.web.dto.Views;
import com.agenticfx.core.application.ExecutionService;
import com.agenticfx.core.port.Repositories;
import com.agenticfx.core.port.Transactions;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Human-in-the-loop. Only a CUSTOMER token can reach these endpoints; the agent token cannot. */
@RestController
@RequestMapping("/approvals")
public class ApprovalController {

    private final ExecutionService execution;
    private final Repositories.Bundle repos;
    private final Transactions tx;
    private final ViewAssembler views;
    private final IdempotencyService idempotency;

    public ApprovalController(ExecutionService execution, Repositories.Bundle repos, Transactions tx,
                              ViewAssembler views, IdempotencyService idempotency) {
        this.execution = execution;
        this.repos = repos;
        this.tx = tx;
        this.views = views;
        this.idempotency = idempotency;
    }

    @GetMapping
    public List<Views.ApprovalView> list(@AuthenticationPrincipal Jwt jwt,
                                         @RequestParam(defaultValue = "pending") String status) {
        boolean pendingOnly = !"all".equalsIgnoreCase(status);
        return tx.inTransaction(() -> repos.approvals().findByUser(CurrentUser.id(jwt), pendingOnly))
                .stream().map(views::approval).toList();
    }

    /** Approves, revalidates the quote, re-runs the Policy Engine and executes if still allowed. */
    @PostMapping("/{approvalId}/approve")
    public ResponseEntity<?> approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID approvalId,
                                     @RequestHeader("Idempotency-Key") String key) {
        UUID userId = CurrentUser.id(jwt);
        return idempotency.execute(userId, key, "approval.approve", approvalId,
                () -> ResponseEntity.ok(views.outcome(execution.approve(approvalId, userId))));
    }

    @PostMapping("/{approvalId}/reject")
    public ResponseEntity<?> reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID approvalId,
                                    @RequestHeader("Idempotency-Key") String key,
                                    @Valid @RequestBody(required = false) Requests.Reject request) {
        UUID userId = CurrentUser.id(jwt);
        String reason = request == null ? null : request.reason();
        return idempotency.execute(userId, key, "approval.reject", approvalId + "|" + reason,
                () -> ResponseEntity.ok(views.outcome(execution.reject(approvalId, userId, reason))));
    }
}
