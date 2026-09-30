package com.agenticfx.core.strategy;

import com.agenticfx.core.Scenario;
import com.agenticfx.core.agent.AgentDecisionType;
import com.agenticfx.core.goal.FxGoal;
import com.agenticfx.core.goal.FxStrategy;
import com.agenticfx.core.goal.GoalStatus;
import com.agenticfx.core.goal.StrategyType;
import com.agenticfx.core.mandate.AgentMandate;
import com.agenticfx.core.market.MarketSimulator;
import com.agenticfx.core.market.RateObservation;
import com.agenticfx.core.money.CurrencyPair;
import com.agenticfx.core.money.ExchangeRate;
import com.agenticfx.core.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.agenticfx.core.money.Currency.BRL;
import static com.agenticfx.core.money.Currency.EUR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyEngineTest {

    private final Scenario s = new Scenario();
    private final StrategyEngine engine = new StrategyEngine(StrategySettings.defaults());

    private FxGoal goal(StrategyType type, BigDecimal targetRate) {
        FxGoal g = new FxGoal(UUID.randomUUID(), s.userId, "Trip", Scenario.BRL_EUR, s.targetAmount, s.deadline,
                s.maxBudget, FxStrategy.of(type, targetRate), GoalStatus.ACTIVE, Money.zero(EUR), Money.zero(BRL),
                Instant.parse("2026-09-20T12:00:00Z"), s.now, 0);
        return g;
    }

    private static RateObservation obs(String rate, String reference) {
        return new RateObservation(new ExchangeRate(Scenario.BRL_EUR, new BigDecimal(rate)),
                new ExchangeRate(Scenario.BRL_EUR, new BigDecimal(reference)), Scenario.NOW, 1);
    }

    private StrategySignal run(FxGoal goal, RateObservation market) {
        return run(goal, market, LocalDate.parse("2026-09-30"));
    }

    private StrategySignal run(FxGoal goal, RateObservation market, LocalDate today) {
        AgentMandate mandate = s.activeMandate(goal);
        return engine.evaluate(new StrategyContext(goal, mandate, market, s.brlBalance, Money.zero(BRL), false,
                Optional.empty(), Optional.empty(), Optional.empty(), s.now, today));
    }

    private static Money proposed(StrategySignal signal) {
        assertTrue(signal instanceof StrategySignal.Propose, signal.toString());
        return ((StrategySignal.Propose) signal).sourceAmount();
    }

    @Test
    void accumulateSizesByStrengthOfImprovement() {
        FxGoal g = goal(StrategyType.ACCUMULATE, null);
        assertEquals(Money.of(2000, BRL), proposed(run(g, obs("0.16665", "0.165"))));   // 100 bps → 50%
        assertEquals(Money.of(3500, BRL), proposed(run(g, obs("0.16810", "0.165"))));   // 188 bps → 94%
        assertEquals(Money.of(4000, BRL), proposed(run(g, obs("0.17500", "0.165"))));   // capped at max per tx
    }

    @Test
    void accumulateWaitsBelowMinimumImprovement() {
        var signal = run(goal(StrategyType.ACCUMULATE, null), obs("0.16570", "0.165"));  // 42 bps < 50
        assertEquals(AgentDecisionType.WAITING_FOR_BETTER_CONDITIONS, signal.type());
        assertTrue(signal.summary().contains("42 bps vs 50 bps"), signal.summary());
    }

    @Test
    void targetRateWaitsThenBuysAsMuchAsAllowed() {
        FxGoal g = goal(StrategyType.TARGET_RATE, new BigDecimal("0.170"));
        assertEquals(AgentDecisionType.WAITING_FOR_BETTER_CONDITIONS, run(g, obs("0.169", "0.165")).type());
        assertEquals(Money.of(4000, BRL), proposed(run(g, obs("0.170", "0.165"))));
    }

    @Test
    void finalTrancheBuysExactlyWhatIsNeeded() {
        FxGoal g = goal(StrategyType.TARGET_RATE, new BigDecimal("0.165"));
        g.recordConversion(Money.of(16000, BRL), Money.of("2900", EUR), s.now);
        // €100 at 0.165 after 0.6% fee → R$ 609.72
        assertEquals(Money.of("609.72", BRL), proposed(run(g, obs("0.165", "0.165"))));
    }

    @Test
    void deadlineAwareRelaxesAsTheDeadlineApproaches() {
        FxGoal g = goal(StrategyType.DEADLINE_AWARE, null);
        assertEquals(AgentDecisionType.WAITING_FOR_BETTER_CONDITIONS,
                run(g, obs("0.16540", "0.165"), LocalDate.parse("2026-09-21")).type(), "24 bps early on");
        assertTrue(run(g, obs("0.16540", "0.165"), LocalDate.parse("2026-10-16")) instanceof StrategySignal.Propose,
                "24 bps is enough close to the deadline");
        var lastDay = run(g, obs("0.16400", "0.165"), LocalDate.parse("2026-10-20"));
        assertTrue(lastDay instanceof StrategySignal.Propose, "buys at market on the final day even if worse");
    }

    @Test
    void allOrNothingGoalThatExceedsOneTransactionWaits() {
        s.allowPartial = false;
        var signal = run(goal(StrategyType.ACCUMULATE, null), obs("0.17", "0.165"));
        assertEquals(AgentDecisionType.NO_ACTION_LIMIT_REACHED, signal.type());
    }

    @Test
    void exhaustedBudgetWaits() {
        FxGoal g = goal(StrategyType.ACCUMULATE, null);
        g.recordConversion(Money.of(18000, BRL), Money.of(2000, EUR), s.now);
        assertEquals(AgentDecisionType.NO_ACTION_LIMIT_REACHED, run(g, obs("0.17", "0.165")).type());
    }

    @Test
    void cooldownAfterAFillUnlessMateriallyBetter() {
        FxGoal g = goal(StrategyType.ACCUMULATE, null);
        AgentMandate mandate = s.activeMandate(g);
        var lastFill = Optional.of(new ExchangeRate(Scenario.BRL_EUR, new BigDecimal("0.1680")));
        var at = Optional.of(s.now.minus(Duration.ofMinutes(5)));
        var same = engine.evaluate(new StrategyContext(g, mandate, obs("0.1681", "0.165"), s.brlBalance,
                Money.zero(BRL), false, lastFill, at, Optional.empty(), s.now, LocalDate.parse("2026-09-30")));
        assertEquals(AgentDecisionType.WAITING_FOR_BETTER_CONDITIONS, same.type());
        var better = engine.evaluate(new StrategyContext(g, mandate, obs("0.1690", "0.165"), s.brlBalance,
                Money.zero(BRL), false, lastFill, at, Optional.empty(), s.now, LocalDate.parse("2026-09-30")));
        assertTrue(better instanceof StrategySignal.Propose);
    }

    @Test
    void neverProposesWhileAnotherProposalIsOpen() {
        FxGoal g = goal(StrategyType.ACCUMULATE, null);
        var signal = engine.evaluate(new StrategyContext(g, s.activeMandate(g), obs("0.18", "0.165"), s.brlBalance,
                Money.zero(BRL), true, Optional.empty(), Optional.empty(), Optional.empty(), s.now,
                LocalDate.parse("2026-09-30")));
        assertTrue(signal instanceof StrategySignal.Wait);
    }

    @Test
    void neverProposesMoreThanTheBalance() {
        s.brlBalance = Money.of(1234, BRL);
        assertEquals(Money.of(1000, BRL), proposed(run(goal(StrategyType.ACCUMULATE, null), obs("0.18", "0.165"))));
    }

    @Test
    void simulatorSupportsInverseDirectionAndTracksReference() {
        Clock clock = Clock.fixed(Scenario.NOW, ZoneOffset.UTC);
        var sim = new MarketSimulator(Map.of(Scenario.BRL_EUR, new BigDecimal("0.165")), clock, 7,
                Duration.ofHours(1), 0, 0);
        var inverse = sim.observe(CurrencyPair.of(EUR, BRL)).orElseThrow();
        assertEquals(0, inverse.rate().rate().compareTo(new BigDecimal("6.06060606")));
        sim.set(CurrencyPair.of(EUR, BRL), new BigDecimal("5.0"));
        var direct = sim.observe(Scenario.BRL_EUR).orElseThrow();
        assertEquals(0, direct.rate().rate().compareTo(new BigDecimal("0.2")));
        assertEquals(0, direct.reference().rate().compareTo(new BigDecimal("0.165")));
    }

    @Test
    void simulatorIsDeterministicForASeed() {
        Clock clock = Clock.fixed(Scenario.NOW, ZoneOffset.UTC);
        var a = new MarketSimulator(MarketSimulator.defaultRates(), clock, 11, Duration.ofHours(1), 5, 0.1);
        var b = new MarketSimulator(MarketSimulator.defaultRates(), clock, 11, Duration.ofHours(1), 5, 0.1);
        for (int i = 0; i < 20; i++) {
            assertEquals(a.tick(), b.tick());
        }
        assertEquals(4, a.snapshot().size());
    }
}
