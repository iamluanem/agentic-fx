package com.agenticfx.core.strategy;

import com.agenticfx.core.agent.AgentDecisionType;
import com.agenticfx.core.goal.FxGoal;
import com.agenticfx.core.goal.FxStrategy;
import com.agenticfx.core.goal.GoalStatus;
import com.agenticfx.core.mandate.AgentMandate;
import com.agenticfx.core.mandate.MandatePermissions;
import com.agenticfx.core.money.Money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import static com.agenticfx.core.money.MoneyFormat.display;

/**
 * Turns market observations into proposals. Pure and deterministic: no I/O, no clock, no randomness.
 * It sizes and times conversions; it has no say over whether they are allowed — that is the Policy Engine's job.
 *
 * <ul>
 *   <li>TARGET_RATE: once the rate reaches the goal's target rate, buy what is still needed (capped by limits).</li>
 *   <li>ACCUMULATE: buy on improvements over the reference rate; the stronger the improvement, the larger the
 *       tranche (full per-transaction size at {@code fullStrengthMultiple} × the minimum improvement).</li>
 *   <li>DEADLINE_AWARE: like ACCUMULATE, but the required improvement shrinks as the deadline approaches and a
 *       minimum pace guarantees the goal can still be completed; in the final day it buys at market.</li>
 * </ul>
 */
public final class StrategyEngine {

    private static final BigDecimal BPS = BigDecimal.valueOf(10_000);

    private final StrategySettings settings;

    public StrategyEngine(StrategySettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public StrategySignal evaluate(StrategyContext ctx) {
        FxGoal goal = ctx.goal();
        AgentMandate mandate = ctx.mandate();
        MandatePermissions perms = mandate.permissions();

        if (goal.status() != GoalStatus.ACTIVE || !mandate.isActive()) {
            return new StrategySignal.Wait(AgentDecisionType.NO_ACTION_LIMIT_REACHED, "Goal is not active.");
        }
        if (goal.isTargetReached()) {
            return new StrategySignal.Wait(AgentDecisionType.GOAL_COMPLETED,
                    "Target of " + display(goal.targetAmount()) + " reached.");
        }
        if (ctx.hasOpenProposal()) {
            return new StrategySignal.Wait(AgentDecisionType.WAITING_FOR_BETTER_CONDITIONS,
                    "A conversion is already awaiting approval or executing.");
        }
        if (!ctx.market().pair().equals(goal.pair())) {
            return new StrategySignal.Wait(AgentDecisionType.WAITING_FOR_BETTER_CONDITIONS,
                    "No market data for " + goal.pair() + ".");
        }
        if (ctx.lastRejectionAt().map(at -> at.plus(settings.cooldown()).isAfter(ctx.now())).orElse(false)) {
            return new StrategySignal.Wait(AgentDecisionType.WAITING_FOR_BETTER_CONDITIONS,
                    "The last proposal was rejected; cooling down before proposing again.");
        }

        Money needed = sourceNeeded(goal.remainingTarget(), ctx);
        Money room = spendRoom(ctx);
        if (!perms.allowPartialExecution() && needed.isGreaterThan(perms.maxTransactionAmount().min(room))) {
            return new StrategySignal.Wait(AgentDecisionType.NO_ACTION_LIMIT_REACHED,
                    "The goal needs a single conversion of about " + display(needed)
                            + ", which exceeds what the mandate or balance allows in one transaction.");
        }
        if (room.isLessThan(needed.min(Money.of(settings.minTranche(), needed.currency())))) {
            return new StrategySignal.Wait(AgentDecisionType.NO_ACTION_LIMIT_REACHED,
                    "Spending room is exhausted (" + display(room) + " left within budget, mandate and balance).");
        }

        FxStrategy strategy = goal.strategy();
        BigDecimal improvement = ctx.market().improvementBps();
        String market = describeMarket(ctx);

        return switch (strategy.type()) {
            case TARGET_RATE -> targetRate(ctx, strategy, needed, room, market);
            case ACCUMULATE -> accumulate(ctx, strategy, improvement, BigDecimal.valueOf(strategy.minImprovementBps()),
                    needed, room, market, Money.zero(needed.currency()),
                    "Progressive accumulation: stronger improvements earn larger tranches");
            case DEADLINE_AWARE -> deadlineAware(ctx, strategy, improvement, needed, room, market);
        };
    }

    private StrategySignal targetRate(StrategyContext ctx, FxStrategy strategy, Money needed, Money room,
                                      String market) {
        BigDecimal rate = ctx.market().rate().rate();
        if (rate.compareTo(strategy.targetRate()) < 0) {
            return new StrategySignal.Wait(AgentDecisionType.WAITING_FOR_BETTER_CONDITIONS,
                    "Rate " + plain(rate) + " is below your target of " + plain(strategy.targetRate()) + ".");
        }
        Money amount = cap(needed, needed, room, ctx);
        return propose(ctx, amount, "The rate reached your target of " + plain(strategy.targetRate()) + ". " + market,
                "Target-rate strategy: convert as much of the remaining goal as the mandate allows.");
    }

    private StrategySignal accumulate(StrategyContext ctx, FxStrategy strategy, BigDecimal improvement,
                                      BigDecimal requiredBps, Money needed, Money room, String market,
                                      Money paceFloor, String rationale) {
        BigDecimal minBps = BigDecimal.valueOf(strategy.minImprovementBps());
        if (ctx.lastFillAt().isPresent() && ctx.lastFillAt().get().plus(settings.cooldown()).isAfter(ctx.now())) {
            BigDecimal lastRate = ctx.lastFillRate().orElseThrow().rate();
            BigDecimal bar = lastRate.multiply(BigDecimal.ONE.add(minBps.divide(BPS, MathContext.DECIMAL64)));
            if (ctx.market().rate().rate().compareTo(bar) < 0 && paceFloor.isZero()) {
                return new StrategySignal.Wait(AgentDecisionType.WAITING_FOR_BETTER_CONDITIONS,
                        "Last bought at " + plain(lastRate) + "; waiting for at least " + minBps
                                + " bps better before buying again.");
            }
        }
        if (improvement.compareTo(requiredBps) < 0 && paceFloor.isZero()) {
            return new StrategySignal.Wait(AgentDecisionType.WAITING_FOR_BETTER_CONDITIONS,
                    "Waiting for " + ctx.goal().pair().target() + "/" + ctx.goal().pair().source()
                            + " conditions to improve (" + improvement.setScale(0, RoundingMode.DOWN) + " bps vs "
                            + requiredBps.setScale(0, RoundingMode.UP) + " bps required).");
        }

        BigDecimal fullAt = minBps.max(BigDecimal.ONE).multiply(settings.fullStrengthMultiple());
        BigDecimal strength = improvement.max(BigDecimal.ZERO).divide(fullAt, MathContext.DECIMAL64).min(BigDecimal.ONE);
        Money strengthAmount = ctx.mandate().permissions().maxTransactionAmount().times(strength);
        Money amount = cap(strengthAmount.max(paceFloor), needed, room, ctx);
        if (amount.isZero()) {
            return new StrategySignal.Wait(AgentDecisionType.WAITING_FOR_BETTER_CONDITIONS,
                    "Improvement is too small to justify a minimum tranche.");
        }
        String sizing = amount.equals(needed)
                ? "This tranche completes the goal."
                : "This tranche is " + strength.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.DOWN)
                  + "% of the per-transaction limit.";
        return propose(ctx, amount, market, rationale + ". " + sizing);
    }

    private StrategySignal deadlineAware(StrategyContext ctx, FxStrategy strategy, BigDecimal improvement,
                                         Money needed, Money room, String market) {
        FxGoal goal = ctx.goal();
        long daysLeft = Math.max(0, ChronoUnit.DAYS.between(ctx.today(), goal.deadline()));
        long horizon = Math.max(1, ChronoUnit.DAYS.between(
                goal.createdAt().atZone(java.time.ZoneOffset.UTC).toLocalDate(), goal.deadline()));
        BigDecimal timeLeft = BigDecimal.valueOf(Math.min(daysLeft, horizon))
                .divide(BigDecimal.valueOf(horizon), MathContext.DECIMAL64);
        BigDecimal required = BigDecimal.valueOf(strategy.minImprovementBps()).multiply(timeLeft);

        long tranchesLeft = Math.max(1, (long) Math.ceil((double) daysLeft * strategy.plannedTranches() / horizon));
        Money pace = daysLeft <= 1
                ? needed
                : needed.times(BigDecimal.ONE.divide(BigDecimal.valueOf(tranchesLeft), MathContext.DECIMAL64));
        // Only enforce the pace when more than one tranche behind schedule (expected vs actual progress).
        BigDecimal expectedProgress = BigDecimal.ONE.subtract(timeLeft);
        BigDecimal oneTranche = BigDecimal.ONE.divide(BigDecimal.valueOf(strategy.plannedTranches()),
                MathContext.DECIMAL64);
        boolean behind = expectedProgress.subtract(goal.progress()).compareTo(oneTranche) > 0;
        Money paceFloor = behind || daysLeft <= 1 ? pace : Money.zero(needed.currency());

        String why = daysLeft <= 1
                ? "Deadline-aware: final day, buying the remainder at market"
                : "Deadline-aware: " + daysLeft + " days left, required improvement relaxed to "
                  + required.setScale(0, RoundingMode.UP) + " bps"
                  + (paceFloor.isPositive() ? " and buying at least the pace needed to finish on time" : "");
        return accumulate(ctx, strategy, improvement, daysLeft <= 1 ? BigDecimal.valueOf(-BPS.intValue()) : required,
                needed, room, market, paceFloor, why);
    }

    // ------------------------------------------------------------------ sizing helpers

    /** Source amount needed to buy {@code remaining} at the observed rate, after the estimated fee. */
    private Money sourceNeeded(Money remaining, StrategyContext ctx) {
        BigDecimal netRate = ctx.market().rate().rate().multiply(BigDecimal.ONE.subtract(settings.feeEstimate()));
        BigDecimal amount = remaining.amount().divide(netRate, 2, RoundingMode.UP);
        return Money.of(amount, ctx.goal().pair().source());
    }

    /** How much can still be spent: the tightest of mandate total, goal budget and available balance. */
    private static Money spendRoom(StrategyContext ctx) {
        FxGoal goal = ctx.goal();
        Money mandateRoom = ctx.mandate().permissions().maxTotalSpend().minus(goal.spent()).minus(ctx.committedSpend());
        Money budgetRoom = goal.maxBudget().minus(goal.spent()).minus(ctx.committedSpend());
        return mandateRoom.min(budgetRoom).min(ctx.availableSource()).nonNegative();
    }

    /** Caps to per-transaction limit and room; partial tranches are rounded down to the increment. */
    private Money cap(Money desired, Money needed, Money room, StrategyContext ctx) {
        Money limit = ctx.mandate().permissions().maxTransactionAmount().min(room);
        if (needed.compareTo(limit) <= 0 && desired.compareTo(needed) >= 0) {
            return needed;
        }
        Money amount = desired.min(limit).min(needed);
        if (amount.equals(needed)) {
            return needed;
        }
        BigDecimal increment = settings.roundingIncrement();
        BigDecimal rounded = amount.amount().divide(increment, 0, RoundingMode.DOWN).multiply(increment);
        if (rounded.compareTo(settings.minTranche()) < 0) {
            rounded = amount.amount().compareTo(settings.minTranche()) >= 0 ? settings.minTranche() : BigDecimal.ZERO;
        }
        return Money.of(rounded, amount.currency());
    }

    private static StrategySignal propose(StrategyContext ctx, Money amount, String whatHappened, String why) {
        MandatePermissions p = ctx.mandate().permissions();
        String rule = "Mandate v" + ctx.mandate().version() + " allows "
                + (p.allowPartialExecution() ? "partial conversions" : "a single conversion")
                + " up to " + display(p.maxTransactionAmount()) + " each and " + display(p.maxTotalSpend())
                + " in total" + p.threshold().map(t -> ", with your approval above " + display(t)).orElse("") + ".";
        return new StrategySignal.Propose(amount, whatHappened, why.endsWith(".") ? why : why + ".", rule);
    }

    private static String describeMarket(StrategyContext ctx) {
        var obs = ctx.market();
        var pair = obs.pair();
        BigDecimal pct = obs.improvementBps().divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN);
        String direction = pct.signum() >= 0 ? "more" : "less";
        return pair.source() + " now buys " + pct.abs().toPlainString() + "% " + direction + " " + pair.target()
                + " than the recent reference rate (" + plain(obs.reference().rate()) + " → "
                + plain(obs.rate().rate()) + ").";
    }

    private static String plain(BigDecimal rate) {
        return rate.setScale(5, RoundingMode.HALF_EVEN).toPlainString();
    }
}
