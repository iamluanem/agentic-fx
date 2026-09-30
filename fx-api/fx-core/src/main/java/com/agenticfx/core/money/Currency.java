package com.agenticfx.core.money;

import java.util.Arrays;
import java.util.Locale;

/** Currencies supported by the platform. Scale is the number of minor-unit digits. */
public enum Currency {
    BRL("R$", 2),
    EUR("€", 2),
    USD("$", 2),
    GBP("£", 2);

    private final String symbol;
    private final int scale;

    Currency(String symbol, int scale) {
        this.symbol = symbol;
        this.scale = scale;
    }

    public String symbol() {
        return symbol;
    }

    public int scale() {
        return scale;
    }

    public static Currency of(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Currency code is required");
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(c -> c.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported currency: " + code));
    }
}
