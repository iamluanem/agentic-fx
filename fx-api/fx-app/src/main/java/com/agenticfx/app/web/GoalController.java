package com.agenticfx.app.web;

import com.agenticfx.app.security.CurrentUser;
import com.agenticfx.app.web.dto.Requests;
import com.agenticfx.app.web.dto.Views;
import com.agenticfx.core.application.GoalService;
import com.agenticfx.core.goal.FxGoal;
import com.agenticfx.core.goal.StrategyType;
import com.agenticfx.core.mandate.ExecutionMode;
import com.agenticfx.core.money.Currency;
import com.agenticfx.core.port.Repositories;
import com.agenticfx.core.port.Transactions;
import com.agenticfx.core.shared.NotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/goals")
public class GoalController {

    private final GoalService goals;
    private final Repositories.Bundle repos;
    private final Transactions tx;
    private final ViewAssembler views;
    private final IdempotencyService idempotency;

    public GoalController(GoalService goals, Repositories.Bundle repos, Transactions tx, ViewAssembler views,
                          IdempotencyService idempotency) {
        this.goals = goals;
        this.repos = repos;
        this.tx = tx;
        this.views = views;
        this.idempotency = idempotency;
    }

    /** Creates goal + mandate v1 as DRAFT. Nothing is authorised until {@code POST /goals/{id}/activate}. */
    @PostMapping
    public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,
                                    @RequestHeader("Idempotency-Key") String key,
                                    @Valid @RequestBody Requests.CreateGoal request) {
        UUID userId = CurrentUser.id(jwt);
        return idempotency.execute(userId, key, "goal.create", request, () -> {
            var m = request.mandate();
            var created = goals.createDraft(new GoalService.CreateGoal(userId, request.name(),
                    Currency.of(request.sourceCurrency()), Currency.of(request.targetCurrency()),
                    request.targetAmount(), request.deadline(), request.maxBudget(),
                    request.strategyType() == null ? StrategyType.ACCUMULATE : StrategyType.valueOf(request.strategyType()),
                    request.targetRate(), request.plannedTranches(), request.minImprovementBps(),
                    terms(m)));
            return ResponseEntity.status(HttpStatus.CREATED).body(new Views.GoalWithMandateView(
                    Views.GoalView.of(created.goal()), Views.MandateView.of(created.mandate())));
        });
    }

    @GetMapping
    public List<Views.GoalView> list(@AuthenticationPrincipal Jwt jwt) {
        return tx.inTransaction(() -> repos.goals().findByUser(CurrentUser.id(jwt)))
                .stream().map(Views.GoalView::of).toList();
    }

    @GetMapping("/{goalId}")
    public Views.GoalDetailView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID goalId) {
        return views.goalDetail(owned(jwt, goalId));
    }

    @PostMapping("/{goalId}/activate")
    public ResponseEntity<?> activate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID goalId,
                                      @RequestHeader("Idempotency-Key") String key) {
        UUID userId = CurrentUser.id(jwt);
        return idempotency.execute(userId, key, "goal.activate", goalId, () -> {
            var result = goals.activate(goalId, userId);
            return ResponseEntity.ok(new Views.GoalWithMandateView(Views.GoalView.of(result.goal()),
                    Views.MandateView.of(result.mandate())));
        });
    }

    @PostMapping("/{goalId}/pause")
    public Views.GoalView pause(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID goalId) {
        return Views.GoalView.of(goals.pause(goalId, CurrentUser.id(jwt)));
    }

    @PostMapping("/{goalId}/resume")
    public Views.GoalView resume(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID goalId) {
        return Views.GoalView.of(goals.resume(goalId, CurrentUser.id(jwt)));
    }

    @PostMapping("/{goalId}/cancel")
    public Views.GoalView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID goalId) {
        return Views.GoalView.of(goals.cancel(goalId, CurrentUser.id(jwt)));
    }

    /** Drafts the next mandate version. The current version stays in force until the new one is activated. */
    @PostMapping("/{goalId}/mandates")
    public ResponseEntity<Views.MandateView> amend(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID goalId,
                                                   @Valid @RequestBody Requests.MandateTerms request) {
        var mandate = goals.amendMandate(goalId, CurrentUser.id(jwt), terms(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(Views.MandateView.of(mandate));
    }

    @PostMapping("/{goalId}/mandates/{version}/activate")
    public ResponseEntity<?> activateMandate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID goalId,
                                             @PathVariable int version,
                                             @RequestHeader("Idempotency-Key") String key) {
        UUID userId = CurrentUser.id(jwt);
        return idempotency.execute(userId, key, "mandate.activate", goalId + "@" + version, () -> ResponseEntity.ok(
                Views.MandateView.of(goals.activateMandateVersion(goalId, version, userId))));
    }

    @GetMapping("/{goalId}/audit")
    public List<Views.AuditView> audit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID goalId) {
        owned(jwt, goalId);
        return tx.inTransaction(() -> repos.audit().findByGoal(goalId)).stream().map(Views.AuditView::of).toList();
    }

    @GetMapping("/{goalId}/conversions")
    public List<Views.ConversionView> conversions(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID goalId) {
        owned(jwt, goalId);
        return tx.inTransaction(() -> repos.conversions().findByGoal(goalId)).stream()
                .map(Views.ConversionView::of).toList();
    }

    @GetMapping("/{goalId}/decisions")
    public List<Views.DecisionView> decisions(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID goalId) {
        owned(jwt, goalId);
        return tx.inTransaction(() -> repos.agentDecisions().findByGoal(goalId, 50)).stream()
                .map(Views.DecisionView::of).toList();
    }

    private FxGoal owned(Jwt jwt, UUID goalId) {
        UUID userId = CurrentUser.id(jwt);
        return tx.inTransaction(() -> repos.goals().find(goalId))
                .filter(g -> g.userId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Goal", goalId));
    }

    private static GoalService.MandateTerms terms(Requests.MandateTerms m) {
        return new GoalService.MandateTerms(m.maxTotalSpend(), m.maxTransactionAmount(), m.approvalThreshold(),
                m.allowPartialExecution() == null || m.allowPartialExecution(),
                ExecutionMode.valueOf(m.executionMode()));
    }
}
