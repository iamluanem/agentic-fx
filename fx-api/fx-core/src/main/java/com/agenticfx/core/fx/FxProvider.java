package com.agenticfx.core.fx;

import com.agenticfx.core.money.Currency;

import java.math.BigDecimal;

/**
 * Port to a liquidity provider (mock, Wise, Revolut, ...). Implementations must be idempotent on
 * {@link ConversionRequest#idempotencyKey()}: repeating a request returns the original result.
 */
public interface FxProvider {

    String name();

    FxQuote getQuote(Currency source, Currency target, BigDecimal amount);

    ConversionResult execute(ConversionRequest request);
}
