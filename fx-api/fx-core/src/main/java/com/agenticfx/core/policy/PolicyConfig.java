package com.agenticfx.core.policy;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Tunables for the standard policy. Values are part of the policy version: changing them is a policy change.
 *
 * @param targetOvershootTolerance how far above the remaining target a conversion may land (0.01 = 1%)
 * @param maxRateSlippageAfterApproval adverse rate move tolerated between approval and execution (0.005 = 0.5%)
 * @param businessZone zone used to decide which calendar day it is for deadlines
 */
public record PolicyConfig(BigDecimal targetOvershootTolerance, BigDecimal maxRateSlippageAfterApproval,
                           ZoneId businessZone) {

    public PolicyConfig {
        Objects.requireNonNull(targetOvershootTolerance, "targetOvershootTolerance");
        Objects.requireNonNull(maxRateSlippageAfterApproval, "maxRateSlippageAfterApproval");
        Objects.requireNonNull(businessZone, "businessZone");
        if (targetOvershootTolerance.signum() < 0 || maxRateSlippageAfterApproval.signum() < 0) {
            throw new IllegalArgumentException("Tolerances cannot be negative");
        }
    }

    public static PolicyConfig defaults() {
        return new PolicyConfig(new BigDecimal("0.01"), new BigDecimal("0.005"), ZoneId.of("America/Sao_Paulo"));
    }
}
