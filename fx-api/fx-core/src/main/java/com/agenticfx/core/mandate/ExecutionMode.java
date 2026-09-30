package com.agenticfx.core.mandate;

/**
 * How much autonomy a mandate grants. The approval threshold, when set, is honoured in both modes.
 * <ul>
 *   <li>{@link #HUMAN_IN_THE_LOOP}: conversions above the threshold need approval; with no threshold, every conversion does.</li>
 *   <li>{@link #AUTONOMOUS}: conversions inside mandate limits execute without approval, except above the threshold.</li>
 * </ul>
 */
public enum ExecutionMode {
    HUMAN_IN_THE_LOOP,
    AUTONOMOUS
}
