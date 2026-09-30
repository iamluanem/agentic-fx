package com.agenticfx.core.policy;

import com.agenticfx.core.account.CurrencyAccount;
import com.agenticfx.core.goal.FxGoal;
import com.agenticfx.core.goal.GoalStatus;
import com.agenticfx.core.money.Currency;
import com.agenticfx.core.money.Money;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Point-in-time facts the Policy Engine evaluates against. Built by the application layer from persisted
 * state inside the same transaction that will execute, never from agent-supplied data.
 *
 * @param balances        available balances per currency
 * @param goal            the goal the proposal belongs to
 * @param committedSpend  source amount of other in-flight proposals for this goal (awaiting approval,
 *                        approved or executing), excluding the proposal being evaluated
 */
public record AccountState(Map<Currency, Money> balances, GoalSnapshot goal, Money committedSpend) {

    public AccountState {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(committedSpend, "committedSpend");
        EnumMap<Currency, Money> copy = new EnumMap<>(Currency.class);
        copy.putAll(balances);
        balances = Map.copyOf(copy);
    }

    public static AccountState of(CurrencyAccount account, FxGoal goal, Money committedSpend) {
        return new AccountState(account.snapshot(), GoalSnapshot.of(goal), committedSpend);
    }

    public Money available(Currency currency) {
        return balances.getOrDefault(currency, Money.zero(currency));
    }

    public record GoalSnapshot(
            UUID goalId,
            GoalStatus status,
            Money targetAmount,
            Money acquired,
            Money spent,
            Money maxBudget,
            LocalDate deadline) {

        public GoalSnapshot {
            Objects.requireNonNull(goalId, "goalId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(targetAmount, "targetAmount");
            Objects.requireNonNull(acquired, "acquired");
            Objects.requireNonNull(spent, "spent");
            Objects.requireNonNull(maxBudget, "maxBudget");
            Objects.requireNonNull(deadline, "deadline");
        }

        public static GoalSnapshot of(FxGoal goal) {
            return new GoalSnapshot(goal.id(), goal.status(), goal.targetAmount(), goal.acquired(), goal.spent(),
                    goal.maxBudget(), goal.deadline());
        }

        public Money remainingTarget() {
            return targetAmount.minus(acquired).nonNegative();
        }
    }
}
