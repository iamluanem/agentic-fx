package com.agenticfx.core.shared;

/** Thrown for missing resources and for resources the caller does not own (to avoid leaking existence). */
public class NotFoundException extends DomainException {
    public NotFoundException(String what, Object id) {
        super("NOT_FOUND", what + " " + id + " not found");
    }
}
