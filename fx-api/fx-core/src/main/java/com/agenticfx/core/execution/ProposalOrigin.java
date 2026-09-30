package com.agenticfx.core.execution;

/** Who created a proposal. Origin is recorded for audit only: it never changes how policy is evaluated. */
public enum ProposalOrigin {
    STRATEGY_ENGINE,
    LLM_AGENT
}
