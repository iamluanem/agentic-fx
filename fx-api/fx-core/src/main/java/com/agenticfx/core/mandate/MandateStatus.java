package com.agenticfx.core.mandate;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum MandateStatus {
    /** Proposed to the user, not yet authorising anything. */
    DRAFT,
    /** Explicitly activated by the user; the only status under which execution can be authorised. */
    ACTIVE,
    /** Temporarily inactive (goal paused). */
    SUSPENDED,
    /** Replaced by a newer version of the same mandate. */
    SUPERSEDED,
    /** Permanently withdrawn (goal cancelled, completed or expired). */
    REVOKED;

    private static final Map<MandateStatus, Set<MandateStatus>> ALLOWED = Map.of(
            DRAFT, EnumSet.of(ACTIVE, REVOKED, SUPERSEDED),
            ACTIVE, EnumSet.of(SUSPENDED, SUPERSEDED, REVOKED),
            SUSPENDED, EnumSet.of(ACTIVE, SUPERSEDED, REVOKED),
            SUPERSEDED, EnumSet.noneOf(MandateStatus.class),
            REVOKED, EnumSet.noneOf(MandateStatus.class));

    public boolean canTransitionTo(MandateStatus next) {
        return ALLOWED.get(this).contains(next);
    }
}
