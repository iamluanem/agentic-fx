package com.agenticfx.core.money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/** A rate for a directional pair: 1 source = {@code rate} target. */
public record ExchangeRate(CurrencyPair pair, BigDecimal rate) {

    public static final int RATE_SCALE = 8;

    public ExchangeRate {
        Objects.requireNonNull(pair, "pair");
        Objects.requireNonNull(rate, "rate");
        if (rate.signum() <= 0) {
            throw new IllegalArgumentException("Rate must be positive: " + rate);
        }
        rate = rate.setScale(RATE_SCALE, RoundingMode.HALF_EVEN);
    }

    /** Converts a source amount, rounding down so the platform never over-credits. */
    public Money convert(Money sourceAmount) {
        if (sourceAmount.currency() != pair.source()) {
            throw new CurrencyMismatchException(pair.source(), sourceAmount.currency());
        }
        return Money.roundedDown(sourceAmount.amount().multiply(rate), pair.target());
    }

    public ExchangeRate inverse() {
        return new ExchangeRate(pair.inverse(), BigDecimal.ONE.divide(rate, MathContext.DECIMAL64));
    }

    /** Relative change versus a reference rate, e.g. 0.018 = 1.8% better for the source holder. */
    public BigDecimal relativeChangeFrom(ExchangeRate reference) {
        if (!reference.pair.equals(pair)) {
            throw new IllegalArgumentException("Pair mismatch: " + reference.pair + " vs " + pair);
        }
        return rate.subtract(reference.rate).divide(reference.rate, MathContext.DECIMAL64);
    }
}
