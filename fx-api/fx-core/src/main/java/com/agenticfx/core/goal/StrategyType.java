package com.agenticfx.core.goal;

public enum StrategyType {
    /** Convert when the market reaches a configured rate. */
    TARGET_RATE,
    /** Split the target into several conversions, buying on favourable moves. */
    ACCUMULATE,
    /** Accumulate, becoming progressively less selective as the deadline approaches. */
    DEADLINE_AWARE
}
