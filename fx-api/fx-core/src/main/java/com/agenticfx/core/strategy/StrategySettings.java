package com.agenticfx.core.strategy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/**
 * @param feeEstimate          fee fraction assumed when sizing (actual fee comes from the quote)
 * @param roundingIncrement    partial tranches are rounded down to this many source units (e.g. R$500)
 * @param minTranche           smallest partial tranche worth proposing
 * @param fullStrengthMultiple improvement (as a multiple of the goal's minimum) that earns a full-size tranche
 * @param cooldown             after a fill, wait this long unless the rate beats the last fill by the minimum;
 *                             after a rejection, wait this long before proposing again
 */
public record StrategySettings(BigDecimal feeEstimate, BigDecimal roundingIncrement, BigDecimal minTranche,
                               BigDecimal fullStrengthMultiple, Duration cooldown) {

    public StrategySettings {
        Objects.requireNonNull(feeEstimate, "feeEstimate");
        Objects.requireNonNull(roundingIncrement, "roundingIncrement");
        Objects.requireNonNull(minTranche, "minTranche");
        Objects.requireNonNull(fullStrengthMultiple, "fullStrengthMultiple");
        Objects.requireNonNull(cooldown, "cooldown");
        if (roundingIncrement.signum() <= 0 || minTranche.signum() <= 0 || fullStrengthMultiple.signum() <= 0) {
            throw new IllegalArgumentException("Strategy settings must be positive");
        }
    }

    public static StrategySettings defaults() {
        return new StrategySettings(new BigDecimal("0.006"), new BigDecimal("500"), new BigDecimal("100"),
                new BigDecimal("4"), Duration.ofHours(6));
    }
}
