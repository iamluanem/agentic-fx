package com.agenticfx.core.fx;

import com.agenticfx.core.money.CurrencyPair;
import com.agenticfx.core.money.ExchangeRate;
import com.agenticfx.core.money.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A firm, time-limited price. The source amount is the gross debit; the fee is taken from it and the
 * remainder is converted: {@code targetAmount = (sourceAmount - fee) * rate}, rounded down.
 */
public record FxQuote(
        UUID id,
        CurrencyPair pair,
        ExchangeRate rate,
        Money sourceAmount,
        Money fee,
        Money targetAmount,
        String provider,
        Instant quotedAt,
        Instant expiresAt) {

    public FxQuote {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(pair, "pair");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(sourceAmount, "sourceAmount");
        Objects.requireNonNull(fee, "fee");
        Objects.requireNonNull(targetAmount, "targetAmount");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(quotedAt, "quotedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!rate.pair().equals(pair)) {
            throw new IllegalArgumentException("Rate pair " + rate.pair() + " does not match quote pair " + pair);
        }
        if (sourceAmount.currency() != pair.source() || fee.currency() != pair.source()) {
            throw new IllegalArgumentException("Source amount and fee must be in " + pair.source());
        }
        if (targetAmount.currency() != pair.target()) {
            throw new IllegalArgumentException("Target amount must be in " + pair.target());
        }
        if (!sourceAmount.isPositive() || fee.isNegative() || !fee.isLessThan(sourceAmount)) {
            throw new IllegalArgumentException("Invalid quote amounts");
        }
        if (!expiresAt.isAfter(quotedAt)) {
            throw new IllegalArgumentException("Quote must expire after it was issued");
        }
    }

    /** Builds a quote whose target amount is derived from rate and fee, so it is internally consistent. */
    public static FxQuote price(CurrencyPair pair, ExchangeRate rate, Money sourceAmount, Money fee,
                                String provider, Instant quotedAt, Instant expiresAt) {
        Money target = rate.convert(sourceAmount.minus(fee));
        return new FxQuote(UUID.randomUUID(), pair, rate, sourceAmount, fee, target, provider, quotedAt, expiresAt);
    }

    /** Expired at or after {@code expiresAt}: a quote is never used at its exact expiry instant. */
    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
