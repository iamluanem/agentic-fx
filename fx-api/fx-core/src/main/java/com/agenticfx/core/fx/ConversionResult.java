package com.agenticfx.core.fx;

import com.agenticfx.core.money.Money;

import java.time.Instant;
import java.util.Objects;

/** Provider response. On success the amounts are what actually settled, which may differ from the quote. */
public record ConversionResult(
        Status status,
        String providerReference,
        Money sourceDebited,
        Money fee,
        Money targetCredited,
        Instant settledAt,
        String failureReason) {

    public enum Status { SUCCEEDED, FAILED }

    public ConversionResult {
        Objects.requireNonNull(status, "status");
        if (status == Status.SUCCEEDED) {
            Objects.requireNonNull(providerReference, "providerReference");
            Objects.requireNonNull(sourceDebited, "sourceDebited");
            Objects.requireNonNull(fee, "fee");
            Objects.requireNonNull(targetCredited, "targetCredited");
            Objects.requireNonNull(settledAt, "settledAt");
        } else {
            Objects.requireNonNull(failureReason, "failureReason");
        }
    }

    public static ConversionResult succeeded(String reference, Money debited, Money fee, Money credited, Instant at) {
        return new ConversionResult(Status.SUCCEEDED, reference, debited, fee, credited, at, null);
    }

    public static ConversionResult failed(String reference, String reason) {
        return new ConversionResult(Status.FAILED, reference, null, null, null, null, reason);
    }

    public boolean succeeded() {
        return status == Status.SUCCEEDED;
    }
}
