package com.agenticfx.core.fx;

import com.agenticfx.core.mandate.MandateRef;
import com.agenticfx.core.money.CurrencyPair;
import com.agenticfx.core.money.ExchangeRate;
import com.agenticfx.core.money.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A settled conversion. Immutable; references the proposal, quote and exact mandate version behind it. */
public record FxConversion(
        UUID id,
        UUID proposalId,
        UUID goalId,
        UUID userId,
        MandateRef mandateRef,
        UUID quoteId,
        String provider,
        String providerReference,
        CurrencyPair pair,
        ExchangeRate rate,
        Money sourceAmount,
        Money fee,
        Money targetAmount,
        Instant executedAt) {

    public FxConversion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(goalId, "goalId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(mandateRef, "mandateRef");
        Objects.requireNonNull(quoteId, "quoteId");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(providerReference, "providerReference");
        Objects.requireNonNull(pair, "pair");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(sourceAmount, "sourceAmount");
        Objects.requireNonNull(fee, "fee");
        Objects.requireNonNull(targetAmount, "targetAmount");
        Objects.requireNonNull(executedAt, "executedAt");
    }
}
