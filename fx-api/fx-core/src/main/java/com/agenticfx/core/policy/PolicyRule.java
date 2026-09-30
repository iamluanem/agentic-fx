package com.agenticfx.core.policy;

import com.agenticfx.core.execution.ExecutionProposal;
import com.agenticfx.core.mandate.AgentMandate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** A single deterministic check. Rules only read their input and append findings. */
@FunctionalInterface
public interface PolicyRule {

    void apply(Input input, List<Finding> findings);

    record Input(AgentMandate mandate, ExecutionProposal proposal, AccountState account,
                 Instant now, LocalDate today, PolicyConfig config) {
    }
}
