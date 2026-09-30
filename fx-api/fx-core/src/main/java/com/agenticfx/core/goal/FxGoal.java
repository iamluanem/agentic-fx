package com.agenticfx.core.goal;

import com.agenticfx.core.money.CurrencyPair;
import com.agenticfx.core.money.Money;
import com.agenticfx.core.shared.InvalidStateTransitionException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A multi-currency goal: acquire {@code targetAmount} of the target currency before {@code deadline},
 * spending at most {@code maxBudget} of the source currency.
 */
public class FxGoal {

    private final UUID id;
    private final UUID userId;
    private final String name;
    private final CurrencyPair pair;
    private final Money targetAmount;
    private final LocalDate deadline;
    private final Money maxBudget;
    private final FxStrategy strategy;
    private GoalStatus status;
    private Money acquired;
    private Money spent;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    public FxGoal(UUID id, UUID userId, String name, CurrencyPair pair, Money targetAmount, LocalDate deadline,
                  Money maxBudget, FxStrategy strategy, GoalStatus status, Money acquired, Money spent,
                  Instant createdAt, Instant updatedAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.name = name == null || name.isBlank() ? pair.target() + " goal" : name.strip();
        this.pair = Objects.requireNonNull(pair, "pair");
        this.targetAmount = requireCurrency(targetAmount, pair.target(), "targetAmount");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.maxBudget = requireCurrency(maxBudget, pair.source(), "maxBudget");
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.status = Objects.requireNonNull(status, "status");
        this.acquired = requireCurrency(acquired, pair.target(), "acquired");
        this.spent = requireCurrency(spent, pair.source(), "spent");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
        if (!targetAmount.isPositive() || !maxBudget.isPositive()) {
            throw new IllegalArgumentException("Target amount and budget must be positive");
        }
    }

    public static FxGoal draft(UUID userId, String name, CurrencyPair pair, Money targetAmount, LocalDate deadline,
                               Money maxBudget, FxStrategy strategy, Instant now) {
        return new FxGoal(UUID.randomUUID(), userId, name, pair, targetAmount, deadline, maxBudget, strategy,
                GoalStatus.DRAFT, Money.zero(pair.target()), Money.zero(pair.source()), now, now, 0);
    }

    public void activate(Instant now) {
        transition(GoalStatus.ACTIVE, now);
    }

    public void pause(Instant now) {
        transition(GoalStatus.PAUSED, now);
    }

    public void resume(Instant now) {
        if (status != GoalStatus.PAUSED) {
            throw new InvalidStateTransitionException("FxGoal", status, GoalStatus.ACTIVE);
        }
        transition(GoalStatus.ACTIVE, now);
    }

    public void cancel(Instant now) {
        transition(GoalStatus.CANCELLED, now);
    }

    public void expire(Instant now) {
        transition(GoalStatus.EXPIRED, now);
    }

    /** Applies a settled conversion. Completes the goal when the target is reached. */
    public void recordConversion(Money sourceSpent, Money targetAcquired, Instant now) {
        requireCurrency(sourceSpent, pair.source(), "sourceSpent");
        requireCurrency(targetAcquired, pair.target(), "targetAcquired");
        if (status != GoalStatus.ACTIVE) {
            throw new InvalidStateTransitionException("FxGoal", status, GoalStatus.ACTIVE);
        }
        spent = spent.plus(sourceSpent);
        acquired = acquired.plus(targetAcquired);
        updatedAt = now;
        if (isTargetReached()) {
            transition(GoalStatus.COMPLETED, now);
        }
    }

    public boolean isTargetReached() {
        return acquired.isGreaterThanOrEqual(targetAmount);
    }

    public Money remainingTarget() {
        return targetAmount.minus(acquired).nonNegative();
    }

    public Money remainingBudget() {
        return maxBudget.minus(spent).nonNegative();
    }

    /** Progress in [0, 1], 4 decimal places. */
    public BigDecimal progress() {
        BigDecimal ratio = acquired.amount().divide(targetAmount.amount(), 4, RoundingMode.DOWN);
        return ratio.min(BigDecimal.ONE);
    }

    public boolean isPastDeadline(LocalDate today) {
        return today.isAfter(deadline);
    }

    private void transition(GoalStatus next, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidStateTransitionException("FxGoal", status, next);
        }
        status = next;
        updatedAt = now;
    }

    private static Money requireCurrency(Money money, com.agenticfx.core.money.Currency currency, String field) {
        Objects.requireNonNull(money, field);
        if (money.currency() != currency) {
            throw new IllegalArgumentException(field + " must be in " + currency + " but was " + money.currency());
        }
        return money;
    }

    /** Called by the persistence adapter after a successful optimistic-locked update. */
    public void versionIncremented() {
        version++;
    }

    public UUID id() { return id; }
    public UUID userId() { return userId; }
    public String name() { return name; }
    public CurrencyPair pair() { return pair; }
    public Money targetAmount() { return targetAmount; }
    public LocalDate deadline() { return deadline; }
    public Money maxBudget() { return maxBudget; }
    public FxStrategy strategy() { return strategy; }
    public GoalStatus status() { return status; }
    public Money acquired() { return acquired; }
    public Money spent() { return spent; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
}
