package com.agenticfx.core.policy;

import com.agenticfx.core.execution.ExecutionProposal;
import com.agenticfx.core.mandate.AgentMandate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Evaluates every rule and aggregates: any DENY finding denies; otherwise any approval finding requires approval;
 * otherwise the proposal is allowed. There is no input — agent text, proposal origin, prompt — that can
 * short-circuit a rule: all rules always run against state the application loaded itself.
 */
public final class DeterministicPolicyEngine implements PolicyEngine {

    private final Policy policy;
    private final PolicyConfig config;
    private final Clock clock;

    public DeterministicPolicyEngine(Policy policy, PolicyConfig config, Clock clock) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static DeterministicPolicyEngine standard(Clock clock) {
        return new DeterministicPolicyEngine(Policy.standard(), PolicyConfig.defaults(), clock);
    }

    @Override
    public PolicyDecision evaluate(AgentMandate mandate, ExecutionProposal proposal, AccountState account) {
        Objects.requireNonNull(mandate, "mandate");
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(account, "account");

        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, config.businessZone());
        var input = new PolicyRule.Input(mandate, proposal, account, now, today, config);

        List<Finding> findings = new ArrayList<>();
        for (PolicyRule rule : policy.rules()) {
            rule.apply(input, findings);
        }

        Decision decision;
        ReasonCode.Effect determining;
        if (has(findings, ReasonCode.Effect.DENY)) {
            decision = Decision.DENY;
            determining = ReasonCode.Effect.DENY;
        } else if (has(findings, ReasonCode.Effect.REQUIRE_APPROVAL)) {
            decision = Decision.REQUIRE_APPROVAL;
            determining = ReasonCode.Effect.REQUIRE_APPROVAL;
        } else {
            decision = Decision.ALLOW;
            determining = ReasonCode.Effect.PERMIT;
        }

        List<Finding> decisive = findings.stream().filter(f -> f.code().effect() == determining).toList();
        String explanation = prefix(decision) + decisive.stream().map(Finding::message).collect(Collectors.joining(" "));

        return new PolicyDecision(decision, decisive.stream().map(Finding::code).toList(), explanation, findings,
                policy.version(), proposal.id(), proposal.mandateRef(), now);
    }

    private static boolean has(List<Finding> findings, ReasonCode.Effect effect) {
        return findings.stream().anyMatch(f -> f.code().effect() == effect);
    }

    private static String prefix(Decision decision) {
        return switch (decision) {
            case ALLOW -> "Allowed. ";
            case REQUIRE_APPROVAL -> "Needs your approval. ";
            case DENY -> "Denied. ";
        };
    }
}
