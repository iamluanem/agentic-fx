package com.agenticfx.core.port;

import com.agenticfx.core.agent.AgentDecisionType;
import com.agenticfx.core.policy.Decision;

/** Business metrics. The application maps these to Micrometer counters. */
public interface Metrics {

    void agentDecision(AgentDecisionType type);

    void policyDecision(Decision decision);

    void approvalRequested();

    void execution(boolean succeeded);

    void goalFinished(boolean completed);

    Metrics NOOP = new Metrics() {
        public void agentDecision(AgentDecisionType type) { }
        public void policyDecision(Decision decision) { }
        public void approvalRequested() { }
        public void execution(boolean succeeded) { }
        public void goalFinished(boolean completed) { }
    };
}
