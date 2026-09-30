package com.agenticfx.core.shared;

/** A request that conflicts with current state (concurrent modification, already-open proposal, ...). */
public class ConflictException extends DomainException {
    public ConflictException(String code, String message) {
        super(code, message);
    }
}
