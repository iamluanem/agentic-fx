package com.agenticfx.core.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.agenticfx.core.money.Currency.BRL;
import static com.agenticfx.core.money.Currency.EUR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    @Test
    void normalisesToCurrencyScaleWithBankersRounding() {
        assertEquals(new BigDecimal("10.12"), Money.of("10.125", BRL).amount());
        assertEquals(new BigDecimal("10.14"), Money.of("10.135", BRL).amount());
    }

    @Test
    void roundedDownNeverOverstates() {
        assertEquals(new BigDecimal("328.01"), Money.roundedDown(new BigDecimal("328.0199"), EUR).amount());
    }

    @Test
    void arithmeticRejectsMixedCurrencies() {
        assertThrows(CurrencyMismatchException.class, () -> Money.of(1, BRL).plus(Money.of(1, EUR)));
        assertThrows(CurrencyMismatchException.class, () -> Money.of(1, BRL).compareTo(Money.of(1, EUR)));
    }

    @Test
    void equalityIgnoresInputScale() {
        assertEquals(Money.of("2000", BRL), Money.of("2000.00", BRL));
    }

    @Test
    void convertsWithRateAndRoundsDown() {
        var rate = new ExchangeRate(CurrencyPair.parse("BRL_EUR"), new BigDecimal("0.165"));
        assertEquals(Money.of("328.02", EUR), rate.convert(Money.of(1988, BRL)));
        assertThrows(CurrencyMismatchException.class, () -> rate.convert(Money.of(1, EUR)));
    }

    @Test
    void parsesPairFormats() {
        assertEquals(CurrencyPair.of(BRL, EUR), CurrencyPair.parse("brl/eur"));
        assertEquals(CurrencyPair.of(BRL, EUR), CurrencyPair.parse("BRLEUR"));
        assertThrows(IllegalArgumentException.class, () -> CurrencyPair.parse("BRL_BRL"));
        assertThrows(IllegalArgumentException.class, () -> CurrencyPair.parse("BRL_XYZ"));
    }

    @Test
    void relativeChangeIsPositiveWhenSourceBuysMore() {
        var pair = CurrencyPair.parse("BRL_EUR");
        var change = new ExchangeRate(pair, new BigDecimal("0.168"))
                .relativeChangeFrom(new ExchangeRate(pair, new BigDecimal("0.165")));
        assertTrue(change.compareTo(new BigDecimal("0.018")) > 0 && change.compareTo(new BigDecimal("0.0183")) < 0);
    }

    @Test
    void formatsForHumans() {
        assertEquals("R$ 32,000.00", MoneyFormat.display(Money.of(32000, BRL)));
        assertEquals("€ 487.32", MoneyFormat.display(Money.of("487.32", EUR)));
    }
}
