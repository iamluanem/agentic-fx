package com.agenticfx.app.web.dto;

import com.agenticfx.core.agent.AgentDecision;
import com.agenticfx.core.agent.Explanation;
import com.agenticfx.core.approval.ApprovalRequest;
import com.agenticfx.core.audit.AuditEvent;
import com.agenticfx.core.execution.ExecutionProposal;
import com.agenticfx.core.fx.FxConversion;
import com.agenticfx.core.fx.FxQuote;
import com.agenticfx.core.goal.FxGoal;
import com.agenticfx.core.mandate.AgentMandate;
import com.agenticfx.core.market.RateObservation;
import com.agenticfx.core.money.Money;
import com.agenticfx.core.money.MoneyFormat;
import com.agenticfx.core.policy.PolicyDecision;
import com.agenticfx.core.policy.ReasonCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response models. The API exposes these views only, never domain objects. */
public final class Views {

    private Views() {
    }

    public record MoneyView(BigDecimal amount, String currency, String display) {
        public static MoneyView of(Money m) {
            return m == null ? null : new MoneyView(m.amount(), m.currency().name(), MoneyFormat.display(m));
        }
    }

    public record StrategyView(String type, BigDecimal targetRate, int plannedTranches, int minImprovementBps) {
    }

    public record GoalView(UUID id, String name, String sourceCurrency, String targetCurrency, MoneyView targetAmount,
                           MoneyView acquired, MoneyView spent, MoneyView maxBudget, MoneyView remainingTarget,
                           MoneyView remainingBudget, LocalDate deadline, StrategyView strategy, String status,
                           BigDecimal progress, Instant createdAt, Instant updatedAt) {
        public static GoalView of(FxGoal g) {
            var s = g.strategy();
            return new GoalView(g.id(), g.name(), g.pair().source().name(), g.pair().target().name(),
                    MoneyView.of(g.targetAmount()), MoneyView.of(g.acquired()), MoneyView.of(g.spent()),
                    MoneyView.of(g.maxBudget()), MoneyView.of(g.remainingTarget()), MoneyView.of(g.remainingBudget()),
                    g.deadline(), new StrategyView(s.type().name(), s.targetRate(), s.plannedTranches(),
                    s.minImprovementBps()), g.status().name(), g.progress(), g.createdAt(), g.updatedAt());
        }
    }

    public record PermissionsView(String sourceCurrency, String targetCurrency, MoneyView maxTotalSpend,
                                  MoneyView maxTransactionAmount, MoneyView approvalThreshold,
                                  boolean allowPartialExecution) {
    }

    public record MandateView(UUID mandateId, UUID goalId, int version, String status, String executionMode,
                              PermissionsView permissions, LocalDate validUntil, Instant createdAt,
                              Instant activatedAt, Instant endedAt) {
        public static MandateView of(AgentMandate m) {
            if (m == null) {
                return null;
            }
            var p = m.permissions();
            return new MandateView(m.id(), m.goalId(), m.version(), m.status().name(), m.executionMode().name(),
                    new PermissionsView(p.sourceCurrency().name(), p.targetCurrency().name(),
                            MoneyView.of(p.maxTotalSpend()), MoneyView.of(p.maxTransactionAmount()),
                            MoneyView.of(p.approvalThreshold()), p.allowPartialExecution()),
                    m.validUntil(), m.createdAt(), m.activatedAt(), m.endedAt());
        }
    }

    public record QuoteView(UUID id, String pair, BigDecimal rate, MoneyView sourceAmount, MoneyView fee,
                            MoneyView targetAmount, String provider, Instant quotedAt, Instant expiresAt) {
        public static QuoteView of(FxQuote q) {
            return new QuoteView(q.id(), q.pair().code(), q.rate().rate().stripTrailingZeros(),
                    MoneyView.of(q.sourceAmount()), MoneyView.of(q.fee()), MoneyView.of(q.targetAmount()),
                    q.provider(), q.quotedAt(), q.expiresAt());
        }
    }

    public record ExplanationView(String whatHappened, String why, MoneyView amountToConvert,
                                  MoneyView expectedAmount, MoneyView fee, BigDecimal rate,
                                  MoneyView remainingTargetAfter, String mandateRule, String narrative) {
        public static ExplanationView of(Explanation e) {
            return e == null ? null : new ExplanationView(e.whatHappened(), e.whyThisAction(),
                    MoneyView.of(e.amountToConvert()), MoneyView.of(e.expectedAmount()), MoneyView.of(e.fee()),
                    e.rate().rate().stripTrailingZeros(), MoneyView.of(e.remainingTargetAfter()), e.mandateRule(),
                    e.narrative());
        }
    }

    public record ProposalView(UUID id, UUID goalId, String status, String origin, UUID mandateId,
                               int mandateVersion, QuoteView quote, ExplanationView explanation,
                               String rejectionSource, String statusReason, Instant createdAt, Instant updatedAt) {
        public static ProposalView of(ExecutionProposal p) {
            return p == null ? null : new ProposalView(p.id(), p.goalId(), p.status().name(), p.origin().name(),
                    p.mandateRef().mandateId(), p.mandateRef().version(), QuoteView.of(p.quote()),
                    ExplanationView.of(p.explanation()), p.rejectionSource().map(Enum::name).orElse(null),
                    p.statusReason().orElse(null), p.createdAt(), p.updatedAt());
        }
    }

    public record PolicyView(String decision, List<String> reasonCodes, String explanation, String policyVersion,
                             Instant evaluatedAt) {
        public static PolicyView of(PolicyDecision d) {
            return d == null ? null : new PolicyView(d.decision().name(),
                    d.reasonCodes().stream().map(ReasonCode::name).toList(), d.explanation(), d.policyVersion(),
                    d.evaluatedAt());
        }
    }

    public record ConversionView(UUID id, UUID proposalId, UUID goalId, String pair, BigDecimal rate,
                                 MoneyView sourceAmount, MoneyView fee, MoneyView targetAmount, String provider,
                                 String providerReference, UUID mandateId, int mandateVersion, Instant executedAt) {
        public static ConversionView of(FxConversion c) {
            return c == null ? null : new ConversionView(c.id(), c.proposalId(), c.goalId(), c.pair().code(),
                    c.rate().rate().stripTrailingZeros(), MoneyView.of(c.sourceAmount()), MoneyView.of(c.fee()),
                    MoneyView.of(c.targetAmount()), c.provider(), c.providerReference(), c.mandateRef().mandateId(),
                    c.mandateRef().version(), c.executedAt());
        }
    }

    public record ApprovalView(UUID id, String status, UUID goalId, String goalName, Instant requestedAt,
                               Instant expiresAt, Instant decidedAt, String note, ProposalView proposal,
                               PolicyView policy) {
        public static ApprovalView of(ApprovalRequest a, String goalName, ExecutionProposal p, PolicyDecision d) {
            return new ApprovalView(a.id(), a.status().name(), a.goalId(), goalName, a.requestedAt(), a.expiresAt(),
                    a.decidedAt(), a.note(), ProposalView.of(p), PolicyView.of(d));
        }
    }

    public record AuditView(UUID id, Instant timestamp, String type, String actor, String summary, UUID goalId,
                            UUID mandateId, Integer mandateVersion, UUID proposalId, String agentDecision,
                            String policyDecision, List<String> reasonCodes, UUID quoteId, String quoteRate,
                            String provider, MoneyView requestedAmount, MoneyView executedAmount,
                            String correlationId) {
        public static AuditView of(AuditEvent e) {
            return new AuditView(e.id(), e.timestamp(), e.type().name(), e.actor(), e.summary(), e.goalId(),
                    e.mandateRef() == null ? null : e.mandateRef().mandateId(),
                    e.mandateRef() == null ? null : e.mandateRef().version(), e.proposalId(), e.agentDecision(),
                    e.policyDecision() == null ? null : e.policyDecision().name(),
                    e.reasonCodes().stream().map(ReasonCode::name).toList(), e.quoteId(), e.quoteRate(), e.provider(),
                    MoneyView.of(e.requestedAmount()), MoneyView.of(e.executedAmount()), e.correlationId());
        }
    }

    public record DecisionView(UUID id, String type, String source, String summary, UUID proposalId,
                               Instant decidedAt) {
        public static DecisionView of(AgentDecision d) {
            return new DecisionView(d.id(), d.type().name(), d.source().name(), d.summary(), d.proposalId(),
                    d.decidedAt());
        }
    }

    /** What the dashboard shows as the agent's live state. */
    public record AgentStatusView(String state, String message) {
    }

    public record GoalDetailView(GoalView goal, MandateView mandate, List<MandateView> mandateVersions,
                                 AgentStatusView agent, ProposalView openProposal, UUID pendingApprovalId,
                                 List<DecisionView> recentDecisions, List<ConversionView> conversions) {
    }

    public record GoalWithMandateView(GoalView goal, MandateView mandate) {
    }

    public record OutcomeView(ProposalView proposal, PolicyView policy, UUID approvalId, String approvalStatus,
                              ConversionView conversion) {
    }

    public record RateView(String pair, BigDecimal rate, BigDecimal reference, BigDecimal changeBps,
                           Instant observedAt) {
        public static RateView of(RateObservation o) {
            return new RateView(o.pair().code(), o.rate().rate().stripTrailingZeros(),
                    o.reference().rate().setScale(6, RoundingMode.HALF_EVEN),
                    o.improvementBps().setScale(1, RoundingMode.HALF_EVEN), o.observedAt());
        }
    }

    public record BalanceView(String currency, MoneyView available) {
    }

    public record TokenView(String accessToken, String tokenType, Instant expiresAt, List<String> roles) {
    }

    public record MeView(UUID id, String email, String displayName, List<String> roles) {
    }
}
