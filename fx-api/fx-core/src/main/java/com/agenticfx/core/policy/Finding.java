package com.agenticfx.core.policy;

import java.util.Objects;

/** One rule outcome: a reason code plus a human-readable, figure-specific message. */
public record Finding(ReasonCode code, String message) {
    public Finding {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
