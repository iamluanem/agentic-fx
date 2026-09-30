package com.agenticfx.core.money;

import java.util.Objects;

/**
 * Directional pair. Rates quoted for a pair mean "units of {@code target} per 1 unit of {@code source}",
 * so BRL_EUR = 0.1652 reads as 1 BRL = 0.1652 EUR.
 */
public record CurrencyPair(Currency source, Currency target) {

    public CurrencyPair {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (source == target) {
            throw new IllegalArgumentException("Source and target currency must differ: " + source);
        }
    }

    public static CurrencyPair of(Currency source, Currency target) {
        return new CurrencyPair(source, target);
    }

    /** Parses {@code BRL_EUR}, {@code BRL/EUR} or {@code BRLEUR}. */
    public static CurrencyPair parse(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Pair code is required");
        }
        String normalized = code.trim().toUpperCase().replace("/", "_");
        if (!normalized.contains("_") && normalized.length() == 6) {
            normalized = normalized.substring(0, 3) + "_" + normalized.substring(3);
        }
        String[] parts = normalized.split("_");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid pair code: " + code);
        }
        return new CurrencyPair(Currency.of(parts[0]), Currency.of(parts[1]));
    }

    public CurrencyPair inverse() {
        return new CurrencyPair(target, source);
    }

    public String code() {
        return source.name() + "_" + target.name();
    }

    @Override
    public String toString() {
        return code();
    }
}
