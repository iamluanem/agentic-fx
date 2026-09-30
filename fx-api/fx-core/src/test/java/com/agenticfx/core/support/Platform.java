package com.agenticfx.core.support;

import com.agenticfx.core.account.Balance;
import com.agenticfx.core.account.CurrencyAccount;
import com.agenticfx.core.application.ExecutionService;
import com.agenticfx.core.application.GoalService;
import com.agenticfx.core.application.StrategyRunner;
import com.agenticfx.core.fx.ConversionRequest;
import com.agenticfx.core.fx.ConversionResult;
import com.agenticfx.core.fx.FxProvider;
import com.agenticfx.core.fx.FxQuote;
import com.agenticfx.core.fx.MockFxProvider;
import com.agenticfx.core.goal.StrategyType;
import com.agenticfx.core.mandate.ExecutionMode;
import com.agenticfx.core.market.MarketSimulator;
import com.agenticfx.core.market.RateObservation;
import com.agenticfx.core.money.Currency;
import com.agenticfx.core.money.CurrencyPair;
import com.agenticfx.core.money.Money;
import com.agenticfx.core.policy.DeterministicPolicyEngine;
import com.agenticfx.core.port.Metrics;
import com.agenticfx.core.port.Repositories;
import com.agenticfx.core.port.RequestContext;
import com.agenticfx.core.strategy.StrategyEngine;
import com.agenticfx.core.strategy.StrategySettings;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.agenticfx.core.money.Currency.BRL;
import static com.agenticfx.core.money.Currency.EUR;
import static com.agenticfx.core.money.Currency.USD;

/** The whole core wired with in-memory adapters and the seeded demo account. */
public final class Platform {

    public static final Instant START = Instant.parse("2026-09-30T15:00:00Z");
    public static final CurrencyPair BRL_EUR = CurrencyPair.parse("BRL_EUR");
    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    public final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public final MutableClock clock = new MutableClock(START);
    public final InMemoryStore store = new InMemoryStore();
    public final Repositories.Bundle repos = store.repositories();
    public final MarketSimulator market;
    public final MockFxProvider mock;
    public final AtomicBoolean providerThrows = new AtomicBoolean(false);
    public final FxProvider provider;
    public final ExecutionService execution;
    public final GoalService goals;
    public final StrategyRunner runner;

    /** Deterministic market (no noise). */
    public Platform() {
        this(0, 0, 42);
    }

    /** Market with the given noise, as configured in production (e.g. 3 bps, 0.2 mean reversion). */
    public Platform(double volatilityBps, double meanReversion, long seed) {
        market = new MarketSimulator(MarketSimulator.defaultRates(), clock, seed, Duration.ofMinutes(60),
                volatilityBps, meanReversion);
        mock = new MockFxProvider(market, new BigDecimal("0.006"), Duration.ofSeconds(30), clock);
        provider = new FxProvider() {
        public String name() { return mock.name(); }
        public FxQuote getQuote(Currency s, Currency t, BigDecimal a) { return mock.getQuote(s, t, a); }
        public ConversionResult execute(ConversionRequest r) {
            if (providerThrows.get()) {
                throw new IllegalStateException("connection reset");
            }
            return mock.execute(r);
        }
        };
        execution = new ExecutionService(repos, DeterministicPolicyEngine.standard(clock), provider, store, store,
                Metrics.NOOP, RequestContext.NONE, clock, Duration.ofHours(24));
        goals = new GoalService(repos, execution, store, store, Metrics.NOOP, RequestContext.NONE, clock, ZONE);
        runner = new StrategyRunner(repos, new StrategyEngine(StrategySettings.defaults()), execution, store, store,
                Metrics.NOOP, RequestContext.NONE, clock, ZONE);
        store.seed(new CurrencyAccount(UUID.randomUUID(), userId, Map.of(
                BRL, new Balance(BRL, Money.of(30000, BRL)),
                EUR, new Balance(EUR, Money.of(500, EUR)),
                USD, new Balance(USD, Money.of(1000, USD))), 0));
    }

    /** The seeded demo goal: €3,000 by 2026-10-20, R$18,000 budget, R$4,000 per tx, approval above R$2,500. */
    public GoalService.CreateGoal demoGoal(ExecutionMode mode, BigDecimal threshold) {
        return new GoalService.CreateGoal(userId, "Europe Trip", BRL, EUR, new BigDecimal("3000"),
                LocalDate.parse("2026-10-20"), new BigDecimal("18000"), StrategyType.ACCUMULATE, null, null, null,
                new GoalService.MandateTerms(new BigDecimal("18000"), new BigDecimal("4000"), threshold, true, mode));
    }

    public UUID activeDemoGoal() {
        UUID goalId = goals.createDraft(demoGoal(ExecutionMode.HUMAN_IN_THE_LOOP, new BigDecimal("2500"))).goal().id();
        goals.activate(goalId, userId);
        return goalId;
    }

    /** Builds a flat reference history at the current rate: {@code n} observations one minute apart. */
    public void steadyMarket(int n) {
        for (int i = 0; i < n; i++) {
            clock.advance(Duration.ofMinutes(1));
            market.tick();
        }
    }

    public List<StrategyRunner.GoalRun> setRate(String rate) {
        clock.advance(Duration.ofSeconds(5));
        RateObservation obs = market.set(BRL_EUR, new BigDecimal(rate));
        return runner.onRate(obs);
    }

    public Money balance(Currency currency) {
        return store.inTransaction(() -> repos.accounts().findByUser(userId).orElseThrow().availableIn(currency));
    }

    public <T> T read(java.util.function.Supplier<T> query) {
        return store.inTransaction(query);
    }
}
