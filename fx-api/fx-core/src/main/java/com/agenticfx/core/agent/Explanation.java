package com.agenticfx.core.agent;

import com.agenticfx.core.money.ExchangeRate;
import com.agenticfx.core.money.Money;

import static com.agenticfx.core.money.MoneyFormat.display;

import java.util.Objects;

/**
 * Structured explanation attached to every recommendation. Numeric fields come from the quote and the goal,
 * never from free text, so the narrative cannot contradict the figures the policy evaluated.
 */
public record Explanation(
        String whatHappened,
        String whyThisAction,
        Money amountToConvert,
        Money expectedAmount,
        Money fee,
        ExchangeRate rate,
        Money remainingTargetAfter,
        String mandateRule) {

    public Explanation {
        Objects.requireNonNull(whatHappened, "whatHappened");
        Objects.requireNonNull(whyThisAction, "whyThisAction");
        Objects.requireNonNull(amountToConvert, "amountToConvert");
        Objects.requireNonNull(expectedAmount, "expectedAmount");
        Objects.requireNonNull(fee, "fee");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(remainingTargetAfter, "remainingTargetAfter");
        Objects.requireNonNull(mandateRule, "mandateRule");
    }

    public String narrative() {
        return String.join(" ",
                whatHappened,
                mandateRule,
                "I recommend converting " + display(amountToConvert) + " into about " + display(expectedAmount)
                        + " at 1 " + rate.pair().source() + " = " + rate.rate().stripTrailingZeros().toPlainString()
                        + " " + rate.pair().target() + " (fee " + display(fee) + ").",
                whyThisAction,
                "After this conversion, " + display(remainingTargetAfter) + " remains to reach the goal.");
    }
}
