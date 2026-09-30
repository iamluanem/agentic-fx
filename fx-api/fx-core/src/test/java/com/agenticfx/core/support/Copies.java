package com.agenticfx.core.support;

import com.agenticfx.core.account.CurrencyAccount;
import com.agenticfx.core.approval.ApprovalRequest;
import com.agenticfx.core.execution.ExecutionProposal;
import com.agenticfx.core.goal.FxGoal;
import com.agenticfx.core.mandate.AgentMandate;

/** Deep-enough copies so in-memory repositories behave like a database (no shared mutable instances). */
final class Copies {

    private Copies() {
    }

    static FxGoal of(FxGoal g) {
        return new FxGoal(g.id(), g.userId(), g.name(), g.pair(), g.targetAmount(), g.deadline(), g.maxBudget(),
                g.strategy(), g.status(), g.acquired(), g.spent(), g.createdAt(), g.updatedAt(), g.version());
    }

    static AgentMandate of(AgentMandate m) {
        return new AgentMandate(m.id(), m.version(), m.goalId(), m.userId(), m.permissions(), m.executionMode(),
                m.validUntil(), m.status(), m.createdAt(), m.activatedAt(), m.endedAt(), m.rowVersion());
    }

    static ExecutionProposal of(ExecutionProposal p) {
        return new ExecutionProposal(p.id(), p.goalId(), p.userId(), p.mandateRef(), p.quote(), p.status(),
                p.origin(), p.explanation(), p.idempotencyKey(), p.approvedRate().orElse(null),
                p.rejectionSource().orElse(null), p.statusReason().orElse(null), p.createdAt(), p.updatedAt(),
                p.version());
    }

    static ApprovalRequest of(ApprovalRequest a) {
        return new ApprovalRequest(a.id(), a.proposalId(), a.goalId(), a.userId(), a.status(), a.requestedAt(),
                a.expiresAt(), a.decidedAt(), a.decidedBy(), a.note(), a.version());
    }

    static CurrencyAccount of(CurrencyAccount a) {
        return new CurrencyAccount(a.id(), a.userId(), a.balances(), a.version());
    }
}
