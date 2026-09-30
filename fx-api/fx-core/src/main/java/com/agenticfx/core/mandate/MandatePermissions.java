package com.agenticfx.core.mandate;

import com.agenticfx.core.money.Currency;
import com.agenticfx.core.money.CurrencyPair;
import com.agenticfx.core.money.Money;

import java.util.Objects;
import java.util.Optional;

/**
 * Exactly what the agent may do. All limits are in the source currency.
 *
 * @param approvalThreshold conversions strictly above this amount need human approval; {@code null} = no threshold
 */
public record MandatePermissions(
        Currency sourceCurrency,
        Currency targetCurrency,
        Money maxTotalSpend,
        Money maxTransactionAmount,
        Money approvalThreshold,
        boolean allowPartialExecution) {

    public MandatePermissions {
        Objects.requireNonNull(sourceCurrency, "sourceCurrency");
        Objects.requireNonNull(targetCurrency, "targetCurrency");
        Objects.requireNonNull(maxTotalSpend, "maxTotalSpend");
        Objects.requireNonNull(maxTransactionAmount, "maxTransactionAmount");
        if (sourceCurrency == targetCurrency) {
            throw new IllegalArgumentException("Source and target currency must differ");
        }
        requireSource(maxTotalSpend, sourceCurrency, "maxTotalSpend");
        requireSource(maxTransactionAmount, sourceCurrency, "maxTransactionAmount");
        if (!maxTotalSpend.isPositive() || !maxTransactionAmount.isPositive()) {
            throw new IllegalArgumentException("Spend limits must be positive");
        }
        if (maxTransactionAmount.isGreaterThan(maxTotalSpend)) {
            throw new IllegalArgumentException("maxTransactionAmount cannot exceed maxTotalSpend");
        }
        if (approvalThreshold != null) {
            requireSource(approvalThreshold, sourceCurrency, "approvalThreshold");
            if (approvalThreshold.isNegative()) {
                throw new IllegalArgumentException("approvalThreshold cannot be negative");
            }
        }
    }

    public CurrencyPair pair() {
        return CurrencyPair.of(sourceCurrency, targetCurrency);
    }

    public Optional<Money> threshold() {
        return Optional.ofNullable(approvalThreshold);
    }

    private static void requireSource(Money money, Currency source, String field) {
        if (money.currency() != source) {
            throw new IllegalArgumentException(field + " must be in " + source);
        }
    }
}
