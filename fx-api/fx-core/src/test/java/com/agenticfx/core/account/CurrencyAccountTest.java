package com.agenticfx.core.account;

import com.agenticfx.core.money.Money;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.agenticfx.core.money.Currency.BRL;
import static com.agenticfx.core.money.Currency.USD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CurrencyAccountTest {

    @Test
    void creditsAndDebits() {
        var account = CurrencyAccount.open(UUID.randomUUID());
        account.credit(Money.of(30000, BRL));
        account.debit(Money.of(2000, BRL));
        assertEquals(Money.of(28000, BRL), account.availableIn(BRL));
        assertEquals(Money.zero(USD), account.availableIn(USD));
    }

    @Test
    void neverGoesNegative() {
        var account = CurrencyAccount.open(UUID.randomUUID());
        account.credit(Money.of(100, BRL));
        assertThrows(InsufficientFundsException.class, () -> account.debit(Money.of("100.01", BRL)));
        assertThrows(IllegalArgumentException.class, () -> account.credit(Money.zero(BRL)));
    }

    @Test
    void snapshotIsImmutable() {
        var account = CurrencyAccount.open(UUID.randomUUID());
        account.credit(Money.of(10, BRL));
        assertThrows(UnsupportedOperationException.class, () -> account.snapshot().put(USD, Money.of(1, USD)));
    }
}
