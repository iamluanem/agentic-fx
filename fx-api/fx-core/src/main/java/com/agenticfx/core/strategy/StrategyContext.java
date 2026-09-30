package com.agenticfx.core.strategy;

import com.agenticfx.core.goal.FxGoal;
import com.agenticfx.core.mandate.AgentMandate;
import com.agenticfx.core.market.RateObservation;
import com.agenticfx.core.money.ExchangeRate;
import com.agenticfx.core.money.Money;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the Strategy Engine needs to decide, loaded by the application layer.
 *
 * @param lastFillRate      rate of the most recent conversion for this goal, if any
 * @param lastFillAt        when it executed
 * @param lastRejectionAt   when a proposal for this goal was last rejected (by policy or by the user)
 */
public record StrategyContext(
        FxGoal goal,
        AgentMandate mandate,
        RateObservation market,
        Money availableSource,
        Money committedSpend,
        boolean hasOpenProposal,
        Optional<ExchangeRate> lastFillRate,
        Optional<Instant> lastFillAt,
        Optional<Instant> lastRejectionAt,
        Instant now,
        LocalDate today) {

    public StrategyContext {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(mandate, "mandate");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(availableSource, "availableSource");
        Objects.requireNonNull(committedSpend, "committedSpend");
        Objects.requireNonNull(lastFillRate, "lastFillRate");
        Objects.requireNonNull(lastFillAt, "lastFillAt");
        Objects.requireNonNull(lastRejectionAt, "lastRejectionAt");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(today, "today");
    }
}
