package com.agenticfx.core.shared;

/** Base for violations of domain invariants. {@code code} is stable and safe to expose to clients. */
public class DomainException extends RuntimeException {

    private final String code;

    public DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
