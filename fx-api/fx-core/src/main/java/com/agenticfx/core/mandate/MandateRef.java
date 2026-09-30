package com.agenticfx.core.mandate;

import java.util.Objects;
import java.util.UUID;

/** Points at the exact mandate version that authorised something. */
public record MandateRef(UUID mandateId, int version) {

    public MandateRef {
        Objects.requireNonNull(mandateId, "mandateId");
        if (version < 1) {
            throw new IllegalArgumentException("Mandate version starts at 1");
        }
    }

    @Override
    public String toString() {
        return mandateId + "@v" + version;
    }
}
