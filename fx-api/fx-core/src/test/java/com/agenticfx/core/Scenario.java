package com.agenticfx.core;

import com.agenticfx.core.agent.Explanation;
import com.agenticfx.core.execution.ExecutionProposal;
import com.agenticfx.core.execution.ProposalOrigin;
import com.agenticfx.core.fx.FxQuote;
import com.agenticfx.core.goal.FxGoal;
import com.agenticfx.core.goal.FxStrategy;
import com.agenticfx.core.goal.GoalStatus;
import com.agenticfx.core.goal.StrategyType;
import com.agenticfx.core.mandate.AgentMandate;
import com.agenticfx.core.mandate.ExecutionMode;
import com.agenticfx.core.mandate.MandatePermissions;
import com.agenticfx.core.money.Currency;
import com.agenticfx.core.money.CurrencyPair;
import com.agenticfx.core.money.ExchangeRate;
import com.agenticfx.core.money.Money;
import com.agenticfx.core.policy.AccountState;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static com.agenticfx.core.money.Currency.BRL;
import static com.agenticfx.core.money.Currency.EUR;

/**
 * Builds consistent test scenarios. Defaults mirror the seeded demo: acquire €3,000 by 2026-10-20 with a
 * R$18,000 budget, R$4,000 per transaction and approval above R$2,500.
 */
public final class Scenario {

    public static final Instant NOW = Instant.parse("2026-09-30T15:00:00Z");
    public static final CurrencyPair BRL_EUR = CurrencyPair.of(BRL, EUR);

    public Instant now = NOW;
    public UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public LocalDate deadline = LocalDate.parse("2026-10-20");
    public Money targetAmount = Money.of(3000, EUR);
    public Money maxBudget = Money.of(18000, BRL);
    public Money maxTotalSpend = Money.of(18000, BRL);
    public Money maxTransaction = Money.of(4000, BRL);
    public Money approvalThreshold = Money.of(2500, BRL);
    public boolean allowPartial = true;
    public ExecutionMode mode = ExecutionMode.HUMAN_IN_THE_LOOP;
    public BigDecimal rate = new BigDecimal("0.165");
    public BigDecimal feeRate = new BigDecimal("0.006");
    public long quoteTtlSeconds = 30;
    public Money brlBalance = Money.of(30000, BRL);
    public Money committed = Money.zero(BRL);

    public Clock clock() {
        return Clock.fixed(now, ZoneOffset.UTC);
    }

    public FxGoal activeGoal() {
        FxGoal goal = FxGoal.draft(userId, "Europe Trip", BRL_EUR, targetAmount, deadline, maxBudget,
                FxStrategy.of(StrategyType.ACCUMULATE, null), now);
        goal.activate(now);
        return goal;
    }

    public FxGoal goalWith(GoalStatus status, Money acquired, Money spent) {
        return new FxGoal(UUID.randomUUID(), userId, "Europe Trip", BRL_EUR, targetAmount, deadline, maxBudget,
                FxStrategy.of(StrategyType.ACCUMULATE, null), status, acquired, spent, now, now, 0);
    }

    public MandatePermissions permissions() {
        return new MandatePermissions(BRL, EUR, maxTotalSpend, maxTransaction, approvalThreshold, allowPartial);
    }

    public AgentMandate activeMandate(FxGoal goal) {
        AgentMandate mandate = AgentMandate.draft(goal.id(), userId, permissions(), mode, deadline, now);
        mandate.activate(now);
        return mandate;
    }

    public FxQuote quote(Money source) {
        return quote(source, rate);
    }

    public FxQuote quote(Money source, BigDecimal atRate) {
        Money fee = source.times(feeRate);
        return FxQuote.price(CurrencyPair.of(source.currency(), source.currency() == BRL ? EUR : BRL),
                new ExchangeRate(CurrencyPair.of(source.currency(), source.currency() == BRL ? EUR : BRL), atRate),
                source, fee, "mock", now, now.plusSeconds(quoteTtlSeconds));
    }

    public ExecutionProposal proposal(AgentMandate mandate, FxQuote quote) {
        return ExecutionProposal.propose(mandate.goalId(), mandate.userId(), mandate.ref(), quote,
                ProposalOrigin.STRATEGY_ENGINE, explanation(quote), UUID.randomUUID().toString(), now);
    }

    public ExecutionProposal proposal(AgentMandate mandate, long brl) {
        return proposal(mandate, quote(Money.of(brl, BRL)));
    }

    public AccountState state(FxGoal goal) {
        Map<Currency, Money> balances = new EnumMap<>(Currency.class);
        balances.put(BRL, brlBalance);
        balances.put(EUR, Money.of(500, EUR));
        return new AccountState(balances, AccountState.GoalSnapshot.of(goal), committed);
    }

    public static Explanation explanation(FxQuote quote) {
        return new Explanation("EUR improved versus the reference rate.", "Part of progressive accumulation.",
                quote.sourceAmount(), quote.targetAmount(), quote.fee(), quote.rate(), quote.targetAmount(),
                "Mandate allows partial conversions.");
    }
}
