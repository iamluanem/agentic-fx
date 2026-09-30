package com.agenticfx.app.web;

import com.agenticfx.app.security.CurrentUser;
import com.agenticfx.app.web.dto.Requests;
import com.agenticfx.app.web.dto.Views;
import com.agenticfx.core.application.ExecutionService;
import com.agenticfx.core.execution.ProposalOrigin;
import com.agenticfx.core.fx.FxProvider;
import com.agenticfx.core.goal.FxGoal;
import com.agenticfx.core.money.Currency;
import com.agenticfx.core.money.Money;
import com.agenticfx.core.port.Repositories;
import com.agenticfx.core.port.Transactions;
import com.agenticfx.core.shared.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The controlled tool surface exposed to the LLM agent (via agent-service). Read tools plus one write tool,
 * {@code create_execution_proposal}, whose output is only a proposal: the Policy Engine decides what happens.
 * There is deliberately no execute or approve tool.
 */
@RestController
@Validated
@RequestMapping("/agent/tools")
public class AgentToolController {

    private final ExecutionService execution;
    private final FxProvider provider;
    private final Repositories.Bundle repos;
    private final Transactions tx;
    private final ViewAssembler views;

    public AgentToolController(ExecutionService execution, FxProvider provider, Repositories.Bundle repos,
                               Transactions tx, ViewAssembler views) {
        this.execution = execution;
        this.provider = provider;
        this.repos = repos;
        this.tx = tx;
        this.views = views;
    }

    /** get_balances */
    @GetMapping("/balances")
    public List<Views.BalanceView> balances(@AuthenticationPrincipal Jwt jwt) {
        return views.balances(CurrentUser.id(jwt));
    }

    /** get_fx_quote — indicative only. */
    @GetMapping("/quote")
    public Views.QuoteView quote(@RequestParam String source, @RequestParam String target,
                                 @RequestParam @DecimalMin(value = "0", inclusive = false) BigDecimal amount) {
        return Views.QuoteView.of(provider.getQuote(Currency.of(source), Currency.of(target), amount));
    }

    /** get_goal (list) */
    @GetMapping("/goals")
    public List<Views.GoalView> goals(@AuthenticationPrincipal Jwt jwt) {
        return tx.inTransaction(() -> repos.goals().findByUser(CurrentUser.id(jwt))).stream()
                .map(Views.GoalView::of).toList();
    }

    /** get_goal */
    @GetMapping("/goals/{goalId}")
    public Views.GoalDetailView goal(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID goalId) {
        return views.goalDetail(owned(jwt, goalId));
    }

    /** get_goal_progress */
    @GetMapping("/goals/{goalId}/progress")
    public Map<String, Object> progress(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID goalId) {
        FxGoal g = owned(jwt, goalId);
        return Map.of("goalId", g.id(), "status", g.status().name(), "progress", g.progress(),
                "acquired", Views.MoneyView.of(g.acquired()), "target", Views.MoneyView.of(g.targetAmount()),
                "remainingTarget", Views.MoneyView.of(g.remainingTarget()),
                "spent", Views.MoneyView.of(g.spent()), "remainingBudget", Views.MoneyView.of(g.remainingBudget()),
                "deadline", g.deadline());
    }

    /**
     * create_execution_proposal / request_user_approval. {@code requireApproval=true} routes an otherwise
     * allowed proposal to the user; the agent can make execution more conservative, never less.
     */
    @PostMapping("/proposals")
    public ResponseEntity<Views.OutcomeView> propose(@AuthenticationPrincipal Jwt jwt,
                                                     @RequestHeader("Idempotency-Key") String key,
                                                     @Valid @RequestBody Requests.AgentProposal request) {
        UUID userId = CurrentUser.id(jwt);
        FxGoal goal = owned(jwt, request.goalId());
        var outcome = execution.propose(new ExecutionService.ProposeCommand(goal.id(), userId,
                Money.of(request.sourceAmount(), goal.pair().source()), ProposalOrigin.LLM_AGENT,
                request.marketContext(), request.rationale(), "agent:" + key, request.requireApproval()));
        return ResponseEntity.status(HttpStatus.CREATED).body(views.outcome(outcome));
    }

    /** get_transaction_status */
    @GetMapping("/proposals/{proposalId}")
    public Views.OutcomeView status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID proposalId) {
        UUID userId = CurrentUser.id(jwt);
        return tx.inTransaction(() -> {
            var p = repos.proposals().find(proposalId).filter(x -> x.userId().equals(userId))
                    .orElseThrow(() -> new NotFoundException("Proposal", proposalId));
            var decision = repos.policyEvaluations().findByProposal(proposalId).stream()
                    .reduce((a, b) -> b).orElse(null);
            var approval = repos.approvals().findPendingByProposal(proposalId).orElse(null);
            return views.outcome(new ExecutionService.Outcome(p, decision, approval,
                    repos.conversions().findByProposal(proposalId).orElse(null)));
        });
    }

    private FxGoal owned(Jwt jwt, UUID goalId) {
        UUID userId = CurrentUser.id(jwt);
        return tx.inTransaction(() -> repos.goals().find(goalId)).filter(g -> g.userId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Goal", goalId));
    }
}
