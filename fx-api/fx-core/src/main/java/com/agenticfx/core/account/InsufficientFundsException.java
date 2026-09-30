package com.agenticfx.core.account;

import com.agenticfx.core.money.Money;
import com.agenticfx.core.shared.DomainException;

public class InsufficientFundsException extends DomainException {
    public InsufficientFundsException(Money requested, Money available) {
        super("INSUFFICIENT_FUNDS", "Requested " + requested + " but only " + available + " is available");
    }
}
