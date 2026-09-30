package com.agenticfx.core.goal;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Strategy configuration attached to a goal.
 *
 * @param targetRate        rate (target per 1 source) that triggers TARGET_RATE; optional floor for the others
 * @param plannedTranches   how many conversions ACCUMULATE / DEADLINE_AWARE aim to split the goal into
 * @param minImprovementBps improvement over the reference rate (basis points) that counts as an opportunity
 */
public record FxStrategy(StrategyType type, BigDecimal targetRate, int plannedTranches, int minImprovementBps) {

    public static final int DEFAULT_TRANCHES = 4;
    public static final int DEFAULT_MIN_IMPROVEMENT_BPS = 50;

    public FxStrategy {
        Objects.requireNonNull(type, "type");
        if (type == StrategyType.TARGET_RATE && targetRate == null) {
            throw new IllegalArgumentException("TARGET_RATE strategy requires a target rate");
        }
        if (targetRate != null && targetRate.signum() <= 0) {
            throw new IllegalArgumentException("Target rate must be positive");
        }
        if (plannedTranches < 1 || plannedTranches > 50) {
            throw new IllegalArgumentException("plannedTranches must be between 1 and 50");
        }
        if (minImprovementBps < 0 || minImprovementBps > 5_000) {
            throw new IllegalArgumentException("minImprovementBps must be between 0 and 5000");
        }
    }

    public static FxStrategy of(StrategyType type, BigDecimal targetRate) {
        return new FxStrategy(type, targetRate, DEFAULT_TRANCHES, DEFAULT_MIN_IMPROVEMENT_BPS);
    }
}
