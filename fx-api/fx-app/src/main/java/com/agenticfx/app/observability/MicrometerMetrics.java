package com.agenticfx.app.observability;

import com.agenticfx.core.agent.AgentDecisionType;
import com.agenticfx.core.policy.Decision;
import com.agenticfx.core.port.Metrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Business metrics exported on /actuator/prometheus:
 * agent_decisions_total, policy_denials_total, approval_requests_total, fx_executions_total,
 * fx_execution_failures_total and goal_completion_ratio.
 */
@Component
public class MicrometerMetrics implements Metrics {

    private final MeterRegistry registry;
    private final Counter approvals;
    private final Counter executions;
    private final Counter failures;

    public MicrometerMetrics(MeterRegistry registry, JdbcClient jdbc) {
        this.registry = registry;
        this.approvals = Counter.builder("approval_requests").description("Approval requests created").register(registry);
        this.executions = Counter.builder("fx_executions").description("Conversions settled").register(registry);
        this.failures = Counter.builder("fx_execution_failures").description("Conversions failed").register(registry);
        Gauge.builder("goal_completion_ratio", jdbc, j -> ratio(j))
                .description("Completed goals / finished goals (completed, cancelled or expired)")
                .register(registry);
    }

    private static double ratio(JdbcClient jdbc) {
        try {
            return jdbc.sql("""
                            SELECT coalesce(avg(CASE WHEN status = 'COMPLETED' THEN 1.0 ELSE 0.0 END), 0)
                            FROM fx_goals WHERE status IN ('COMPLETED', 'CANCELLED', 'EXPIRED')""")
                    .query(Double.class).single();
        } catch (RuntimeException unavailable) {
            return Double.NaN;
        }
    }

    @Override
    public void agentDecision(AgentDecisionType type) {
        registry.counter("agent_decisions", "type", type.name()).increment();
    }

    @Override
    public void policyDecision(Decision decision) {
        registry.counter("policy_decisions", "decision", decision.name()).increment();
        if (decision == Decision.DENY) {
            registry.counter("policy_denials").increment();
        }
    }

    @Override
    public void approvalRequested() {
        approvals.increment();
    }

    @Override
    public void execution(boolean succeeded) {
        (succeeded ? executions : failures).increment();
    }

    @Override
    public void goalFinished(boolean completed) {
        registry.counter("goals_finished", "outcome", completed ? "completed" : "not_completed").increment();
    }
}
