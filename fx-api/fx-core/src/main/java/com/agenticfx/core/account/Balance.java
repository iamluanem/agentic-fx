package com.agenticfx.core.account;

import com.agenticfx.core.money.Currency;
import com.agenticfx.core.money.Money;

import java.util.Objects;

/** Available funds in one currency of a {@link CurrencyAccount}. */
public record Balance(Currency currency, Money available) {

    public Balance {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(available, "available");
        if (available.currency() != currency) {
            throw new IllegalArgumentException("Balance currency mismatch");
        }
        if (available.isNegative()) {
            throw new IllegalArgumentException("Balance cannot be negative: " + available);
        }
    }

    public static Balance empty(Currency currency) {
        return new Balance(currency, Money.zero(currency));
    }
}
