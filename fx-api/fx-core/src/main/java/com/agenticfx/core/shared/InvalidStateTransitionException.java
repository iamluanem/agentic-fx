package com.agenticfx.core.shared;

public class InvalidStateTransitionException extends DomainException {
    public InvalidStateTransitionException(String entity, Enum<?> from, Enum<?> to) {
        super("INVALID_STATE_TRANSITION", entity + " cannot transition from " + from + " to " + to);
    }
}
