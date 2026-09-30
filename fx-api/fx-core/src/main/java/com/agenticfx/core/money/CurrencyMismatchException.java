package com.agenticfx.core.money;

import com.agenticfx.core.shared.DomainException;

public class CurrencyMismatchException extends DomainException {
    public CurrencyMismatchException(Currency expected, Currency actual) {
        super("CURRENCY_MISMATCH", "Expected " + expected + " but got " + actual);
    }
}
