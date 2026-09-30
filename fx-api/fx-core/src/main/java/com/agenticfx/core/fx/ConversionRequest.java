package com.agenticfx.core.fx;

import java.util.Objects;
import java.util.UUID;

/** Instruction sent to a provider to execute a previously issued quote. */
public record ConversionRequest(String idempotencyKey, UUID userId, FxQuote quote) {

    public ConversionRequest {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(quote, "quote");
    }
}
