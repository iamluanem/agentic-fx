package com.agenticfx.core.goal;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum GoalStatus {
    DRAFT,
    ACTIVE,
    PAUSED,
    COMPLETED,
    CANCELLED,
    EXPIRED;

    private static final Map<GoalStatus, Set<GoalStatus>> ALLOWED = Map.of(
            DRAFT, EnumSet.of(ACTIVE, CANCELLED),
            ACTIVE, EnumSet.of(PAUSED, COMPLETED, CANCELLED, EXPIRED),
            PAUSED, EnumSet.of(ACTIVE, CANCELLED, EXPIRED),
            COMPLETED, EnumSet.noneOf(GoalStatus.class),
            CANCELLED, EnumSet.noneOf(GoalStatus.class),
            EXPIRED, EnumSet.noneOf(GoalStatus.class));

    public boolean canTransitionTo(GoalStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }
}
