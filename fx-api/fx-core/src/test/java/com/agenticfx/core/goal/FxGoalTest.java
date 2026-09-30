package com.agenticfx.core.goal;

import com.agenticfx.core.Scenario;
import com.agenticfx.core.money.Money;
import com.agenticfx.core.shared.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static com.agenticfx.core.money.Currency.BRL;
import static com.agenticfx.core.money.Currency.EUR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FxGoalTest {

    private final Scenario s = new Scenario();

    @Test
    void draftStartsEmpty() {
        FxGoal goal = FxGoal.draft(s.userId, "Trip", Scenario.BRL_EUR, s.targetAmount, s.deadline, s.maxBudget,
                FxStrategy.of(StrategyType.ACCUMULATE, null), s.now);
        assertEquals(GoalStatus.DRAFT, goal.status());
        assertEquals(Money.zero(EUR), goal.acquired());
        assertEquals(Money.of(3000, EUR), goal.remainingTarget());
    }

    @Test
    void recordsProgressAndCompletesAtTarget() {
        FxGoal goal = s.activeGoal();
        goal.recordConversion(Money.of(2000, BRL), Money.of("328.02", EUR), s.now);
        assertEquals(new BigDecimal("0.1093"), goal.progress());
        assertEquals(Money.of(16000, BRL), goal.remainingBudget());
        assertEquals(GoalStatus.ACTIVE, goal.status());

        goal.recordConversion(Money.of(16000, BRL), Money.of("2671.98", EUR), s.now);
        assertTrue(goal.isTargetReached());
        assertEquals(GoalStatus.COMPLETED, goal.status());
        assertEquals(BigDecimal.ONE, goal.progress().stripTrailingZeros());
    }

    @Test
    void conversionsOnlyApplyToActiveGoals() {
        FxGoal goal = s.activeGoal();
        goal.pause(s.now);
        assertThrows(InvalidStateTransitionException.class,
                () -> goal.recordConversion(Money.of(1, BRL), Money.of(1, EUR), s.now));
    }

    @Test
    void enforcesLifecycle() {
        FxGoal goal = s.activeGoal();
        goal.pause(s.now);
        goal.resume(s.now);
        goal.cancel(s.now);
        assertThrows(InvalidStateTransitionException.class, () -> goal.activate(s.now));
        assertTrue(GoalStatus.CANCELLED.isTerminal());
        assertFalse(GoalStatus.DRAFT.canTransitionTo(GoalStatus.COMPLETED));
    }

    @Test
    void rejectsAmountsInWrongCurrency() {
        assertThrows(IllegalArgumentException.class, () -> FxGoal.draft(s.userId, "x", Scenario.BRL_EUR,
                Money.of(3000, BRL), s.deadline, s.maxBudget, FxStrategy.of(StrategyType.ACCUMULATE, null), s.now));
    }

    @Test
    void deadlineIsInclusive() {
        FxGoal goal = s.activeGoal();
        assertFalse(goal.isPastDeadline(LocalDate.parse("2026-10-20")));
        assertTrue(goal.isPastDeadline(LocalDate.parse("2026-10-21")));
    }

    @Test
    void targetRateStrategyNeedsARate() {
        assertThrows(IllegalArgumentException.class, () -> FxStrategy.of(StrategyType.TARGET_RATE, null));
    }
}
