package com.agenticfx.core.application;

import com.agenticfx.core.audit.AuditEventType;
import com.agenticfx.core.event.DomainEvent;
import com.agenticfx.core.goal.FxGoal;
import com.agenticfx.core.goal.FxStrategy;
import com.agenticfx.core.goal.GoalStatus;
import com.agenticfx.core.goal.StrategyType;
import com.agenticfx.core.mandate.AgentMandate;
import com.agenticfx.core.mandate.ExecutionMode;
import com.agenticfx.core.mandate.MandatePermissions;
import com.agenticfx.core.mandate.MandateRef;
import com.agenticfx.core.mandate.MandateStatus;
import com.agenticfx.core.money.Currency;
import com.agenticfx.core.money.CurrencyPair;
import com.agenticfx.core.money.Money;
import com.agenticfx.core.port.EventPublisher;
import com.agenticfx.core.port.Metrics;
import com.agenticfx.core.port.Repositories;
import com.agenticfx.core.port.RequestContext;
import com.agenticfx.core.port.Transactions;
import com.agenticfx.core.shared.ConflictException;
import com.agenticfx.core.shared.NotFoundException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.agenticfx.core.money.MoneyFormat.display;

/** Goal and mandate lifecycle. Every change that grants authority requires an explicit user action. */
public final class GoalService {

    public record MandateTerms(BigDecimal maxTotalSpend, BigDecimal maxTransactionAmount,
                               BigDecimal approvalThreshold, boolean allowPartialExecution, ExecutionMode mode) {
        public MandateTerms {
            Objects.requireNonNull(maxTotalSpend, "maxTotalSpend");
            Objects.requireNonNull(maxTransactionAmount, "maxTransactionAmount");
            Objects.requireNonNull(mode, "mode");
        }

        MandatePermissions toPermissions(CurrencyPair pair) {
            Currency s = pair.source();
            return new MandatePermissions(s, pair.target(), Money.of(maxTotalSpend, s),
                    Money.of(maxTransactionAmount, s),
                    approvalThreshold == null ? null : Money.of(approvalThreshold, s), allowPartialExecution);
        }
    }

    public record CreateGoal(UUID userId, String name, Currency sourceCurrency, Currency targetCurrency,
                             BigDecimal targetAmount, LocalDate deadline, BigDecimal maxBudget,
                             StrategyType strategyType, BigDecimal targetRate, Integer plannedTranches,
                             Integer minImprovementBps, MandateTerms terms) {
    }

    public record GoalWithMandate(FxGoal goal, AgentMandate mandate) {
    }

    private final Repositories.Bundle repos;
    private final ExecutionService execution;
    private final EventPublisher events;
    private final Transactions tx;
    private final Metrics metrics;
    private final Clock clock;
    private final ZoneId zone;
    private final Audit audit;

    public GoalService(Repositories.Bundle repos, ExecutionService execution, EventPublisher events,
                       Transactions tx, Metrics metrics, RequestContext context, Clock clock, ZoneId zone) {
        this.repos = repos;
        this.execution = execution;
        this.events = events;
        this.tx = tx;
        this.metrics = metrics;
        this.clock = clock;
        this.zone = zone;
        this.audit = new Audit(clock, context);
    }

    /** Creates the goal and mandate v1, both as DRAFT. Nothing is authorised until {@link #activate}. */
    public GoalWithMandate createDraft(CreateGoal cmd) {
        return tx.inTransaction(() -> {
            Instant now = clock.instant();
            if (cmd.deadline().isBefore(today())) {
                throw new IllegalArgumentException("Deadline must not be in the past");
            }
            CurrencyPair pair = CurrencyPair.of(cmd.sourceCurrency(), cmd.targetCurrency());
            FxStrategy strategy = new FxStrategy(cmd.strategyType(), cmd.targetRate(),
                    cmd.plannedTranches() == null ? FxStrategy.DEFAULT_TRANCHES : cmd.plannedTranches(),
                    cmd.minImprovementBps() == null ? FxStrategy.DEFAULT_MIN_IMPROVEMENT_BPS : cmd.minImprovementBps());
            Money budget = Money.of(cmd.maxBudget(), pair.source());
            FxGoal goal = FxGoal.draft(cmd.userId(), cmd.name(), pair, Money.of(cmd.targetAmount(), pair.target()),
                    cmd.deadline(), budget, strategy, now);
            MandatePermissions permissions = cmd.terms().toPermissions(pair);
            if (permissions.maxTotalSpend().isGreaterThan(budget)) {
                throw new IllegalArgumentException("Mandate total spend cannot exceed the goal budget");
            }
            AgentMandate mandate = AgentMandate.draft(goal.id(), cmd.userId(), permissions, cmd.terms().mode(),
                    cmd.deadline(), now);
            repos.goals().insert(goal);
            repos.mandates().insert(mandate);

            String actor = Audit.user(cmd.userId());
            repos.audit().append(audit.event(AuditEventType.GOAL_CREATED, cmd.userId(), actor).goalId(goal.id())
                    .summary("Goal \"" + goal.name() + "\" drafted: acquire " + display(goal.targetAmount())
                            + " by " + goal.deadline() + " spending at most " + display(goal.maxBudget())
                            + " (" + strategy.type() + ").")
                    .build());
            repos.audit().append(audit.event(AuditEventType.MANDATE_DRAFTED, cmd.userId(), actor).goalId(goal.id())
                    .mandate(mandate.ref())
                    .summary("Mandate v1 drafted (" + mandate.executionMode() + "). "
                            + ExecutionService.describeRule(mandate))
                    .build());
            events.publish(new DomainEvent.GoalCreated(UUID.randomUUID(), now, goal.id(), cmd.userId()));
            return new GoalWithMandate(goal, mandate);
        });
    }

    /** Explicit user confirmation: activates the goal and its draft mandate together. */
    public GoalWithMandate activate(UUID goalId, UUID actor) {
        return tx.inTransaction(() -> {
            Instant now = clock.instant();
            FxGoal goal = owned(goalId, actor);
            if (goal.isPastDeadline(today())) {
                throw new ConflictException("DEADLINE_PASSED", "Goal deadline has already passed");
            }
            AgentMandate mandate = repos.mandates().findLatest(goalId)
                    .filter(m -> m.status() == MandateStatus.DRAFT)
                    .orElseThrow(() -> new ConflictException("NO_DRAFT_MANDATE", "There is no draft mandate to activate"));
            goal.activate(now);
            mandate.activate(now);
            repos.goals().update(goal);
            repos.mandates().update(mandate);
            String by = Audit.user(actor);
            repos.audit().append(audit.event(AuditEventType.MANDATE_ACTIVATED, actor, by).goalId(goalId)
                    .mandate(mandate.ref()).summary("You activated mandate v" + mandate.version() + ".").build());
            repos.audit().append(audit.event(AuditEventType.GOAL_ACTIVATED, actor, by).goalId(goalId)
                    .mandate(mandate.ref()).summary("Agent activated; monitoring " + goal.pair() + ".").build());
            events.publish(new DomainEvent.GoalActivated(UUID.randomUUID(), now, goalId, actor, mandate.id(),
                    mandate.version()));
            return new GoalWithMandate(goal, mandate);
        });
    }

    public FxGoal pause(UUID goalId, UUID actor) {
        return tx.inTransaction(() -> {
            FxGoal goal = owned(goalId, actor);
            execution.supersedeOpenProposal(goalId, "Goal paused");
            goal.pause(clock.instant());
            repos.goals().update(goal);
            repos.mandates().findActive(goalId).ifPresent(m -> {
                m.suspend();
                repos.mandates().update(m);
            });
            repos.audit().append(audit.event(AuditEventType.GOAL_PAUSED, actor, Audit.user(actor)).goalId(goalId)
                    .summary("You paused the agent. Open proposals were discarded.").build());
            return goal;
        });
    }

    public FxGoal resume(UUID goalId, UUID actor) {
        return tx.inTransaction(() -> {
            FxGoal goal = owned(goalId, actor);
            if (goal.isPastDeadline(today())) {
                throw new ConflictException("DEADLINE_PASSED", "Goal deadline has already passed");
            }
            goal.resume(clock.instant());
            repos.goals().update(goal);
            repos.mandates().findAll(goalId).stream()
                    .filter(m -> m.status() == MandateStatus.SUSPENDED)
                    .forEach(m -> {
                        m.resume();
                        repos.mandates().update(m);
                    });
            repos.audit().append(audit.event(AuditEventType.GOAL_RESUMED, actor, Audit.user(actor)).goalId(goalId)
                    .summary("You resumed the agent.").build());
            return goal;
        });
    }

    public FxGoal cancel(UUID goalId, UUID actor) {
        return tx.inTransaction(() -> {
            Instant now = clock.instant();
            FxGoal goal = owned(goalId, actor);
            execution.supersedeOpenProposal(goalId, "Goal cancelled");
            goal.cancel(now);
            repos.goals().update(goal);
            revokeAll(goalId, now);
            metrics.goalFinished(false);
            repos.audit().append(audit.event(AuditEventType.GOAL_CANCELLED, actor, Audit.user(actor)).goalId(goalId)
                    .summary("You cancelled the goal; the mandate was revoked.").build());
            return goal;
        });
    }

    /** Proposes new terms as the next mandate version (DRAFT). The current version stays in force. */
    public AgentMandate amendMandate(UUID goalId, UUID actor, MandateTerms terms) {
        return tx.inTransaction(() -> {
            Instant now = clock.instant();
            FxGoal goal = owned(goalId, actor);
            if (goal.status().isTerminal()) {
                throw new ConflictException("GOAL_CLOSED", "Goal is " + goal.status());
            }
            AgentMandate latest = repos.mandates().findLatest(goalId).orElseThrow();
            MandatePermissions permissions = terms.toPermissions(goal.pair());
            if (permissions.maxTotalSpend().isGreaterThan(goal.maxBudget())) {
                throw new IllegalArgumentException("Mandate total spend cannot exceed the goal budget");
            }
            AgentMandate next = latest.amend(permissions, terms.mode(), goal.deadline(), now);
            if (latest.status() == MandateStatus.DRAFT) {
                latest.supersede(now);
                repos.mandates().update(latest);
            }
            repos.mandates().insert(next);
            repos.audit().append(audit.event(AuditEventType.MANDATE_DRAFTED, actor, Audit.user(actor)).goalId(goalId)
                    .mandate(next.ref())
                    .summary("Mandate v" + next.version() + " drafted (" + next.executionMode() + "). "
                            + ExecutionService.describeRule(next))
                    .build());
            return next;
        });
    }

    /** Explicitly activates a drafted mandate version on an already running goal, superseding the current one. */
    public AgentMandate activateMandateVersion(UUID goalId, int version, UUID actor) {
        return tx.inTransaction(() -> {
            Instant now = clock.instant();
            FxGoal goal = owned(goalId, actor);
            AgentMandate draft = repos.mandates().find(new MandateRef(latestId(goalId), version))
                    .filter(m -> m.status() == MandateStatus.DRAFT)
                    .orElseThrow(() -> new ConflictException("NO_DRAFT_MANDATE", "Version " + version + " is not a draft"));
            if (goal.status() == GoalStatus.DRAFT) {
                throw new ConflictException("GOAL_NOT_ACTIVE", "Activate the goal instead");
            }
            if (goal.status().isTerminal()) {
                throw new ConflictException("GOAL_CLOSED", "Goal is " + goal.status());
            }
            execution.supersedeOpenProposal(goalId, "Mandate replaced by v" + version);
            boolean wasSuspended = false;
            for (AgentMandate current : repos.mandates().findAll(goalId)) {
                if (current.status() == MandateStatus.ACTIVE || current.status() == MandateStatus.SUSPENDED) {
                    wasSuspended |= current.status() == MandateStatus.SUSPENDED;
                    current.supersede(now);
                    repos.mandates().update(current);
                    repos.audit().append(audit.event(AuditEventType.MANDATE_SUPERSEDED, actor, Audit.user(actor))
                            .goalId(goalId).mandate(current.ref())
                            .summary("Mandate v" + current.version() + " superseded by v" + version + ".").build());
                }
            }
            draft.activate(now);
            if (wasSuspended || goal.status() == GoalStatus.PAUSED) {
                draft.suspend();
            }
            repos.mandates().update(draft);
            repos.audit().append(audit.event(AuditEventType.MANDATE_ACTIVATED, actor, Audit.user(actor)).goalId(goalId)
                    .mandate(draft.ref()).summary("You activated mandate v" + version + ".").build());
            return draft;
        });
    }

    /** Sweeper: goals past their deadline expire and lose their mandate. */
    public int expireOverdue() {
        List<FxGoal> candidates = tx.inTransaction(() -> repos.goals().findUnfinished());
        LocalDate today = today();
        int expired = 0;
        for (FxGoal candidate : candidates) {
            if (!candidate.isPastDeadline(today)) {
                continue;
            }
            try {
                tx.run(() -> {
                    Instant now = clock.instant();
                    FxGoal goal = repos.goals().find(candidate.id()).orElseThrow();
                    execution.supersedeOpenProposal(goal.id(), "Goal expired");
                    goal.expire(now);
                    repos.goals().update(goal);
                    revokeAll(goal.id(), now);
                    metrics.goalFinished(false);
                    repos.audit().append(audit.event(AuditEventType.GOAL_EXPIRED, goal.userId(), "system")
                            .goalId(goal.id()).summary("Deadline passed with " + display(goal.acquired()) + " of "
                                    + display(goal.targetAmount()) + " acquired; the mandate was revoked.").build());
                });
                expired++;
            } catch (ConflictException busy) {
                // a conversion is executing; the next sweep will expire it
            }
        }
        return expired;
    }

    private void revokeAll(UUID goalId, Instant now) {
        repos.mandates().findAll(goalId).stream()
                .filter(m -> m.status() != MandateStatus.SUPERSEDED && m.status() != MandateStatus.REVOKED)
                .forEach(m -> {
                    m.revoke(now);
                    repos.mandates().update(m);
                });
    }

    private UUID latestId(UUID goalId) {
        return repos.mandates().findLatest(goalId).map(AgentMandate::id)
                .orElseThrow(() -> new NotFoundException("Mandate for goal", goalId));
    }

    private FxGoal owned(UUID goalId, UUID actor) {
        return repos.goals().find(goalId).filter(g -> g.userId().equals(actor))
                .orElseThrow(() -> new NotFoundException("Goal", goalId));
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone);
    }
}
