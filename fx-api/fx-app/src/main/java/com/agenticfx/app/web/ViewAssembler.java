package com.agenticfx.app.web;

import com.agenticfx.app.web.dto.Views;
import com.agenticfx.core.application.ExecutionService;
import com.agenticfx.core.application.StrategyRunner;
import com.agenticfx.core.approval.ApprovalRequest;
import com.agenticfx.core.execution.ExecutionProposal;
import com.agenticfx.core.execution.ExecutionStatus;
import com.agenticfx.core.goal.FxGoal;
import com.agenticfx.core.goal.GoalStatus;
import com.agenticfx.core.mandate.AgentMandate;
import com.agenticfx.core.policy.PolicyDecision;
import com.agenticfx.core.port.Repositories;
import com.agenticfx.core.port.Transactions;
import com.agenticfx.core.strategy.StrategySignal;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Builds composite read models. Each method reads inside one transaction for a consistent snapshot. */
@Component
public class ViewAssembler {

    private final Repositories.Bundle repos;
    private final Transactions tx;
    private final StrategyRunner strategy;

    public ViewAssembler(Repositories.Bundle repos, Transactions tx, StrategyRunner strategy) {
        this.repos = repos;
        this.tx = tx;
        this.strategy = strategy;
    }

    public Views.GoalDetailView goalDetail(FxGoal goal) {
        return tx.inTransaction(() -> {
            List<AgentMandate> versions = repos.mandates().findAll(goal.id());
            AgentMandate current = versions.stream()
                    .filter(m -> m.status().name().equals("ACTIVE") || m.status().name().equals("SUSPENDED"))
                    .findFirst()
                    .orElse(versions.isEmpty() ? null : versions.getLast());
            Optional<ExecutionProposal> open = repos.proposals().findOpen(goal.id());
            UUID pendingApproval = open.flatMap(p -> repos.approvals().findPendingByProposal(p.id()))
                    .map(ApprovalRequest::id).orElse(null);
            return new Views.GoalDetailView(Views.GoalView.of(goal), Views.MandateView.of(current),
                    versions.stream().map(Views.MandateView::of).toList(), agentStatus(goal, open),
                    open.map(Views.ProposalView::of).orElse(null), pendingApproval,
                    repos.agentDecisions().findByGoal(goal.id(), 10).stream().map(Views.DecisionView::of).toList(),
                    repos.conversions().findByGoal(goal.id()).stream().map(Views.ConversionView::of).toList());
        });
    }

    public Views.ApprovalView approval(ApprovalRequest approval) {
        return tx.inTransaction(() -> {
            ExecutionProposal proposal = repos.proposals().find(approval.proposalId()).orElse(null);
            String goalName = repos.goals().find(approval.goalId()).map(FxGoal::name).orElse(null);
            PolicyDecision latest = proposal == null ? null
                    : repos.policyEvaluations().findByProposal(proposal.id()).stream()
                    .max(Comparator.comparing(PolicyDecision::evaluatedAt)).orElse(null);
            return Views.ApprovalView.of(approval, goalName, proposal, latest);
        });
    }

    public Views.OutcomeView outcome(ExecutionService.Outcome o) {
        return new Views.OutcomeView(Views.ProposalView.of(o.proposal()), Views.PolicyView.of(o.decision()),
                o.approval() == null ? null : o.approval().id(),
                o.approval() == null ? null : o.approval().status().name(),
                Views.ConversionView.of(o.conversion()));
    }

    public List<Views.BalanceView> balances(UUID userId) {
        var account = tx.inTransaction(() -> repos.accounts().findByUser(userId))
                .orElseThrow(() -> new com.agenticfx.core.shared.NotFoundException("Account for user", userId));
        return account.balances().values().stream()
                .sorted(Comparator.comparing(b -> b.currency().ordinal()))
                .map(b -> new Views.BalanceView(b.currency().name(), Views.MoneyView.of(b.available())))
                .toList();
    }

    private Views.AgentStatusView agentStatus(FxGoal goal, Optional<ExecutionProposal> open) {
        if (goal.status() != GoalStatus.ACTIVE) {
            return new Views.AgentStatusView(goal.status().name(), switch (goal.status()) {
                case DRAFT -> "Waiting for you to review and activate the mandate.";
                case PAUSED -> "Paused by you. No conversions will be proposed.";
                case COMPLETED -> "Goal reached.";
                case CANCELLED -> "Goal cancelled.";
                case EXPIRED -> "Deadline passed.";
                default -> "";
            });
        }
        if (open.isPresent()) {
            ExecutionProposal p = open.get();
            return p.status() == ExecutionStatus.AWAITING_APPROVAL
                    ? new Views.AgentStatusView("AWAITING_APPROVAL", "Waiting for your approval to convert "
                    + com.agenticfx.core.money.MoneyFormat.display(p.sourceAmount()) + ".")
                    : new Views.AgentStatusView("EXECUTING", "Executing a conversion.");
        }
        return strategy.latestSignal(goal.id())
                .map(signal -> new Views.AgentStatusView(
                        signal instanceof StrategySignal.Propose ? "PROPOSING" : "MONITORING", signal.summary()))
                .orElse(new Views.AgentStatusView("MONITORING", "Monitoring " + goal.pair().target() + "/"
                        + goal.pair().source() + "."));
    }
}
