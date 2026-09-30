package com.agenticfx.core.application;

import com.agenticfx.core.agent.AgentDecision;
import com.agenticfx.core.agent.AgentDecisionType;
import com.agenticfx.core.audit.AuditEventType;
import com.agenticfx.core.event.DomainEvent;
import com.agenticfx.core.execution.ExecutionProposal;
import com.agenticfx.core.execution.ProposalOrigin;
import com.agenticfx.core.fx.FxConversion;
import com.agenticfx.core.goal.FxGoal;
import com.agenticfx.core.mandate.AgentMandate;
import com.agenticfx.core.market.RateObservation;
import com.agenticfx.core.money.Money;
import com.agenticfx.core.port.EventPublisher;
import com.agenticfx.core.port.Metrics;
import com.agenticfx.core.port.Repositories;
import com.agenticfx.core.port.RequestContext;
import com.agenticfx.core.port.Transactions;
import com.agenticfx.core.shared.DomainException;
import com.agenticfx.core.strategy.StrategyContext;
import com.agenticfx.core.strategy.StrategyEngine;
import com.agenticfx.core.strategy.StrategySignal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Market event → Strategy Engine → ExecutionService. Runs once per rate update for every ACTIVE goal on that pair.
 * Waiting decisions are recorded only when the agent's state changes, so the audit trail stays readable.
 */
public final class StrategyRunner {

    public record GoalRun(UUID goalId, StrategySignal signal, ExecutionService.Outcome outcome) {
    }

    private final Repositories.Bundle repos;
    private final StrategyEngine engine;
    private final ExecutionService execution;
    private final EventPublisher events;
    private final Transactions tx;
    private final Metrics metrics;
    private final Clock clock;
    private final ZoneId zone;
    private final Audit audit;
    private final Map<UUID, StrategySignal> latest = new ConcurrentHashMap<>();

    public StrategyRunner(Repositories.Bundle repos, StrategyEngine engine, ExecutionService execution,
                          EventPublisher events, Transactions tx, Metrics metrics, RequestContext context,
                          Clock clock, ZoneId zone) {
        this.repos = repos;
        this.engine = engine;
        this.execution = execution;
        this.events = events;
        this.tx = tx;
        this.metrics = metrics;
        this.clock = clock;
        this.zone = zone;
        this.audit = new Audit(clock, context);
    }

    public List<GoalRun> onRate(RateObservation observation) {
        List<FxGoal> goals = tx.inTransaction(() -> repos.goals().findActiveByPair(observation.pair()));
        List<GoalRun> runs = new ArrayList<>();
        for (FxGoal goal : goals) {
            try {
                runs.add(run(goal.id(), observation));
            } catch (DomainException skipped) {
                // Lost a race (proposal opened concurrently, version conflict): the next tick re-evaluates.
            }
        }
        return runs;
    }

    /** Latest strategy signal per goal — what the dashboard shows as "agent status". */
    public Optional<StrategySignal> latestSignal(UUID goalId) {
        return Optional.ofNullable(latest.get(goalId));
    }

    private GoalRun run(UUID goalId, RateObservation obs) {
        Optional<StrategyContext> context = tx.inTransaction(() -> buildContext(goalId, obs));
        if (context.isEmpty()) {
            return new GoalRun(goalId, null, null);
        }
        StrategyContext ctx = context.get();
        StrategySignal signal = engine.evaluate(ctx);
        latest.put(goalId, signal);

        if (signal instanceof StrategySignal.Wait wait) {
            tx.run(() -> recordWaitIfChanged(ctx, wait));
            return new GoalRun(goalId, signal, null);
        }

        StrategySignal.Propose propose = (StrategySignal.Propose) signal;
        FxGoal goal = ctx.goal();
        tx.run(() -> {
            Instant now = clock.instant();
            repos.agentDecisions().insert(AgentDecision.of(goalId, goal.userId(),
                    AgentDecisionType.MARKET_CONDITION_DETECTED, ProposalOrigin.STRATEGY_ENGINE,
                    propose.whatHappened(), null, null, now));
            metrics.agentDecision(AgentDecisionType.MARKET_CONDITION_DETECTED);
            repos.audit().append(audit.event(AuditEventType.MARKET_CONDITION_DETECTED, goal.userId(), "strategy-engine")
                    .goalId(goalId).mandate(ctx.mandate().ref())
                    .quote(null, obs.rate().rate().toPlainString(), null)
                    .summary("Market condition detected. " + propose.whatHappened())
                    .build());
            events.publish(new DomainEvent.MarketConditionMatched(UUID.randomUUID(), now, goalId, goal.userId(),
                    obs.pair().code(), obs.rate().rate(), obs.improvementBps()));
        });
        var outcome = execution.propose(new ExecutionService.ProposeCommand(goalId, goal.userId(),
                propose.sourceAmount(), ProposalOrigin.STRATEGY_ENGINE, propose.whatHappened(), propose.why(),
                "strategy:" + goalId + ":" + obs.sequence(), false));
        return new GoalRun(goalId, signal, outcome);
    }

    private Optional<StrategyContext> buildContext(UUID goalId, RateObservation obs) {
        Optional<FxGoal> goal = repos.goals().find(goalId);
        Optional<AgentMandate> mandate = repos.mandates().findActive(goalId);
        if (goal.isEmpty() || mandate.isEmpty()) {
            return Optional.empty();
        }
        FxGoal g = goal.get();
        Money available = repos.accounts().findByUser(g.userId())
                .map(a -> a.availableIn(g.pair().source()))
                .orElse(Money.zero(g.pair().source()));
        List<ExecutionProposal> proposals = repos.proposals().findByGoal(goalId);
        Money committed = proposals.stream().filter(p -> p.status().isInFlight())
                .map(ExecutionProposal::sourceAmount).reduce(Money.zero(g.pair().source()), Money::plus);
        boolean open = repos.proposals().findOpen(goalId).isPresent();
        Optional<FxConversion> lastFill = repos.conversions().findByGoal(goalId).stream()
                .max(Comparator.comparing(FxConversion::executedAt));
        Instant now = clock.instant();
        return Optional.of(new StrategyContext(g, mandate.get(), obs, available, committed, open,
                lastFill.map(FxConversion::rate), lastFill.map(FxConversion::executedAt),
                repos.proposals().lastRejectionAt(goalId), now, LocalDate.ofInstant(now, zone)));
    }

    private void recordWaitIfChanged(StrategyContext ctx, StrategySignal.Wait wait) {
        UUID goalId = ctx.goal().id();
        Optional<AgentDecision> last = repos.agentDecisions().findByGoal(goalId, 1).stream().findFirst();
        if (last.isPresent() && last.get().type() == wait.type()) {
            return;
        }
        Instant now = clock.instant();
        repos.agentDecisions().insert(AgentDecision.of(goalId, ctx.goal().userId(), wait.type(),
                ProposalOrigin.STRATEGY_ENGINE, wait.summary(), null, null, now));
        metrics.agentDecision(wait.type());
        repos.audit().append(audit.event(AuditEventType.AGENT_WAITING, ctx.goal().userId(), "strategy-engine")
                .goalId(goalId).mandate(ctx.mandate().ref()).agentDecision(wait.type().name())
                .summary(wait.summary()).build());
    }
}
