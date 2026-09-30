package com.agenticfx.core.policy;

import com.agenticfx.core.execution.ExecutionProposal;
import com.agenticfx.core.mandate.AgentMandate;

/** The single authority on whether a proposal may execute. Implementations must be deterministic and side-effect free. */
public interface PolicyEngine {

    PolicyDecision evaluate(AgentMandate mandate, ExecutionProposal proposal, AccountState account);
}
