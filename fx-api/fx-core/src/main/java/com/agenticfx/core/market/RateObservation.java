package com.agenticfx.core.market;

import com.agenticfx.core.money.CurrencyPair;
import com.agenticfx.core.money.ExchangeRate;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.Objects;

/**
 * A market observation with the reference rate it is judged against.
 *
 * @param reference average rate over the look-back window before this observation
 * @param sequence  monotonically increasing per simulator, useful as an idempotency component
 */
public record RateObservation(ExchangeRate rate, ExchangeRate reference, Instant observedAt, long sequence) {

    public RateObservation {
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(observedAt, "observedAt");
        if (!rate.pair().equals(reference.pair())) {
            throw new IllegalArgumentException("Rate and reference pairs differ");
        }
    }

    public CurrencyPair pair() {
        return rate.pair();
    }

    /** Improvement for a holder of the source currency, in basis points (positive = source buys more). */
    public BigDecimal improvementBps() {
        return rate.relativeChangeFrom(reference).multiply(BigDecimal.valueOf(10_000), MathContext.DECIMAL64);
    }

    public RateObservation inverse() {
        return new RateObservation(rate.inverse(), reference.inverse(), observedAt, sequence);
    }
}
