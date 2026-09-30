package com.agenticfx.app.scheduling;

import com.agenticfx.app.config.AgenticFxProperties;
import com.agenticfx.core.application.ExecutionService;
import com.agenticfx.core.application.GoalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Housekeeping: expire stale approvals and goals, and re-drive executions interrupted by a crash. */
@Component
public class Sweepers {

    private static final Logger log = LoggerFactory.getLogger(Sweepers.class);

    private final ExecutionService execution;
    private final GoalService goals;
    private final AgenticFxProperties properties;

    public Sweepers(ExecutionService execution, GoalService goals, AgenticFxProperties properties) {
        this.execution = execution;
        this.goals = goals;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT10S")
    public void approvals() {
        guarded("approval expiry", () -> execution.expireApprovals());
    }

    @Scheduled(fixedDelayString = "PT15S", initialDelayString = "PT15S")
    public void executions() {
        guarded("execution recovery", () -> execution.recoverExecutions(properties.execution().recoverAfter()));
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT20S")
    public void goals() {
        guarded("goal expiry", goals::expireOverdue);
    }

    private static void guarded(String name, java.util.function.IntSupplier job) {
        try {
            int n = job.getAsInt();
            if (n > 0) {
                log.info("{}: {} item(s) processed", name, n);
            }
        } catch (RuntimeException e) {
            log.warn("{} failed: {}", name, e.getMessage());
        }
    }
}
