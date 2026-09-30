package com.agenticfx.core.policy;

import com.agenticfx.core.execution.ExecutionStatus;
import com.agenticfx.core.goal.GoalStatus;
import com.agenticfx.core.mandate.ExecutionMode;
import com.agenticfx.core.mandate.MandatePermissions;
import com.agenticfx.core.money.ExchangeRate;
import com.agenticfx.core.money.Money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Objects;

import static com.agenticfx.core.money.MoneyFormat.display;

/**
 * A versioned, ordered set of deterministic rules. The version is stored with every decision so an auditor
 * can tell exactly which rules authorised a conversion.
 */
public record Policy(String version, List<PolicyRule> rules) {

    public static final String STANDARD_VERSION = "standard-v1";

    public Policy {
        Objects.requireNonNull(version, "version");
        rules = List.copyOf(rules);
    }

    public static Policy standard() {
        return new Policy(STANDARD_VERSION, List.of(
                Policy::mandateIsActive,
                Policy::mandateMatchesProposal,
                Policy::mandateNotExpired,
                Policy::goalIsActive,
                Policy::deadlineNotPassed,
                Policy::proposalIsEvaluable,
                Policy::currenciesAllowed,
                Policy::quoteNotExpired,
                Policy::sufficientBalance,
                Policy::maxTransactionAmount,
                Policy::maxTotalSpend,
                Policy::goalBudget,
                Policy::remainingTarget,
                Policy::approvalRequirement));
    }

    // ---------------------------------------------------------------- mandate & goal state

    static void mandateIsActive(PolicyRule.Input in, List<Finding> out) {
        if (!in.mandate().isActive()) {
            out.add(new Finding(ReasonCode.MANDATE_NOT_ACTIVE,
                    "Mandate v" + in.mandate().version() + " is " + in.mandate().status() + ", not ACTIVE."));
        }
    }

    static void mandateMatchesProposal(PolicyRule.Input in, List<Finding> out) {
        var mandate = in.mandate();
        var proposal = in.proposal();
        boolean matches = proposal.mandateRef().equals(mandate.ref())
                && proposal.goalId().equals(mandate.goalId())
                && proposal.userId().equals(mandate.userId())
                && in.account().goal().goalId().equals(mandate.goalId());
        if (!matches) {
            out.add(new Finding(ReasonCode.MANDATE_REFERENCE_MISMATCH,
                    "Proposal references mandate " + proposal.mandateRef() + " but the evaluated mandate is "
                            + mandate.ref() + " for another goal or user."));
        }
    }

    static void mandateNotExpired(PolicyRule.Input in, List<Finding> out) {
        if (in.mandate().isExpiredOn(in.today())) {
            out.add(new Finding(ReasonCode.MANDATE_EXPIRED,
                    "Mandate was valid until " + in.mandate().validUntil() + "."));
        }
    }

    static void goalIsActive(PolicyRule.Input in, List<Finding> out) {
        GoalStatus status = in.account().goal().status();
        if (status != GoalStatus.ACTIVE) {
            out.add(new Finding(ReasonCode.GOAL_NOT_ACTIVE, "Goal is " + status + ", not ACTIVE."));
        }
    }

    static void deadlineNotPassed(PolicyRule.Input in, List<Finding> out) {
        if (in.today().isAfter(in.account().goal().deadline())) {
            out.add(new Finding(ReasonCode.DEADLINE_PASSED,
                    "Goal deadline " + in.account().goal().deadline() + " has passed."));
        }
    }

    static void proposalIsEvaluable(PolicyRule.Input in, List<Finding> out) {
        ExecutionStatus status = in.proposal().status();
        if (status != ExecutionStatus.PROPOSED && status != ExecutionStatus.APPROVED) {
            out.add(new Finding(ReasonCode.PROPOSAL_NOT_EVALUABLE,
                    "Proposal is " + status + "; only PROPOSED or APPROVED proposals can be evaluated."));
        }
    }

    // ---------------------------------------------------------------- instrument & quote

    static void currenciesAllowed(PolicyRule.Input in, List<Finding> out) {
        MandatePermissions p = in.mandate().permissions();
        var pair = in.proposal().quote().pair();
        if (pair.source() != p.sourceCurrency()) {
            out.add(new Finding(ReasonCode.SOURCE_CURRENCY_NOT_ALLOWED,
                    "Mandate only allows spending " + p.sourceCurrency() + ", proposal spends " + pair.source() + "."));
        }
        if (pair.target() != p.targetCurrency()) {
            out.add(new Finding(ReasonCode.TARGET_CURRENCY_NOT_ALLOWED,
                    "Mandate only allows buying " + p.targetCurrency() + ", proposal buys " + pair.target() + "."));
        }
    }

    static void quoteNotExpired(PolicyRule.Input in, List<Finding> out) {
        var quote = in.proposal().quote();
        if (quote.isExpiredAt(in.now())) {
            out.add(new Finding(ReasonCode.QUOTE_EXPIRED,
                    "Quote " + quote.id() + " expired at " + quote.expiresAt() + "."));
        }
    }

    // ---------------------------------------------------------------- amounts (skipped on currency mismatch)

    static void sufficientBalance(PolicyRule.Input in, List<Finding> out) {
        if (!sourceMatches(in)) return;
        Money amount = in.proposal().sourceAmount();
        Money available = in.account().available(amount.currency());
        if (available.isLessThan(amount)) {
            out.add(new Finding(ReasonCode.INSUFFICIENT_BALANCE,
                    "Conversion needs " + display(amount) + " but only " + display(available) + " is available."));
        }
    }

    static void maxTransactionAmount(PolicyRule.Input in, List<Finding> out) {
        if (!sourceMatches(in)) return;
        Money amount = in.proposal().sourceAmount();
        Money limit = in.mandate().permissions().maxTransactionAmount();
        if (amount.isGreaterThan(limit)) {
            out.add(new Finding(ReasonCode.MAX_TRANSACTION_AMOUNT_EXCEEDED,
                    display(amount) + " exceeds the " + display(limit) + " per-transaction limit."));
        }
    }

    static void maxTotalSpend(PolicyRule.Input in, List<Finding> out) {
        if (!sourceMatches(in)) return;
        Money projected = projectedSpend(in);
        Money limit = in.mandate().permissions().maxTotalSpend();
        if (projected.isGreaterThan(limit)) {
            out.add(new Finding(ReasonCode.MAX_TOTAL_SPEND_EXCEEDED,
                    "Total spend would reach " + display(projected) + ", above the mandate limit of "
                            + display(limit) + "."));
        }
    }

    static void goalBudget(PolicyRule.Input in, List<Finding> out) {
        if (!sourceMatches(in)) return;
        Money projected = projectedSpend(in);
        Money budget = in.account().goal().maxBudget();
        if (projected.currency() == budget.currency() && projected.isGreaterThan(budget)) {
            out.add(new Finding(ReasonCode.GOAL_BUDGET_EXCEEDED,
                    "Total spend would reach " + display(projected) + ", above the goal budget of "
                            + display(budget) + "."));
        }
    }

    static void remainingTarget(PolicyRule.Input in, List<Finding> out) {
        if (!targetMatches(in)) return;
        var goal = in.account().goal();
        Money remaining = goal.remainingTarget();
        Money buying = in.proposal().quote().targetAmount();
        if (!remaining.isPositive()) {
            out.add(new Finding(ReasonCode.GOAL_ALREADY_REACHED,
                    "Goal target of " + display(goal.targetAmount()) + " is already reached."));
            return;
        }
        Money ceiling = remaining.times(BigDecimal.ONE.add(in.config().targetOvershootTolerance()));
        if (buying.isGreaterThan(ceiling)) {
            out.add(new Finding(ReasonCode.EXCEEDS_REMAINING_TARGET,
                    "Conversion buys " + display(buying) + " but only " + display(remaining)
                            + " remains to reach the target."));
        }
        if (!in.mandate().permissions().allowPartialExecution() && buying.isLessThan(remaining)) {
            out.add(new Finding(ReasonCode.PARTIAL_EXECUTION_NOT_ALLOWED,
                    "Mandate requires a single conversion covering the remaining " + display(remaining)
                            + "; this one buys " + display(buying) + "."));
        }
    }

    // ---------------------------------------------------------------- approval

    static void approvalRequirement(PolicyRule.Input in, List<Finding> out) {
        if (!sourceMatches(in)) return;
        var mandate = in.mandate();
        var proposal = in.proposal();
        Money amount = proposal.sourceAmount();
        var threshold = mandate.permissions().threshold();

        boolean aboveThreshold = threshold.isPresent() && amount.isGreaterThan(threshold.get());
        boolean hitlWithoutThreshold = mandate.executionMode() == ExecutionMode.HUMAN_IN_THE_LOOP
                && threshold.isEmpty();

        if (!aboveThreshold && !hitlWithoutThreshold) {
            out.add(new Finding(ReasonCode.WITHIN_MANDATE_LIMITS,
                    "Mandate v" + mandate.version() + " allows conversions up to "
                            + display(mandate.permissions().maxTransactionAmount()) + " per transaction."));
            if (threshold.isPresent()) {
                out.add(new Finding(ReasonCode.WITHIN_APPROVAL_THRESHOLD,
                        display(amount) + " is within the " + display(threshold.get()) + " approval threshold."));
            }
            if (mandate.executionMode() == ExecutionMode.AUTONOMOUS) {
                out.add(new Finding(ReasonCode.AUTONOMOUS_EXECUTION_PERMITTED,
                        "Mandate runs in AUTONOMOUS mode."));
            }
            return;
        }

        if (proposal.isApproved()) {
            ExchangeRate approved = proposal.approvedRate().orElseThrow();
            ExchangeRate current = proposal.quote().rate();
            BigDecimal adverseMove = approved.rate().subtract(current.rate())
                    .divide(approved.rate(), MathContext.DECIMAL64);
            if (adverseMove.compareTo(in.config().maxRateSlippageAfterApproval()) > 0) {
                out.add(new Finding(ReasonCode.RATE_MOVED_SINCE_APPROVAL,
                        "Rate moved from " + approved.rate().stripTrailingZeros().toPlainString() + " to "
                                + current.rate().stripTrailingZeros().toPlainString()
                                + " since you approved; please confirm again."));
            } else {
                out.add(new Finding(ReasonCode.HUMAN_APPROVAL_GRANTED,
                        "You approved this conversion and the revalidated rate is within tolerance."));
                out.add(new Finding(ReasonCode.WITHIN_MANDATE_LIMITS,
                        "Mandate v" + mandate.version() + " allows conversions up to "
                                + display(mandate.permissions().maxTransactionAmount()) + " per transaction."));
            }
            return;
        }

        if (aboveThreshold) {
            out.add(new Finding(ReasonCode.TRANSACTION_ABOVE_APPROVAL_THRESHOLD,
                    display(amount) + " is above your " + display(threshold.get()) + " approval threshold."));
        } else {
            out.add(new Finding(ReasonCode.HUMAN_IN_THE_LOOP_REQUIRES_APPROVAL,
                    "Mandate is HUMAN_IN_THE_LOOP without a threshold, so every conversion needs your approval."));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static boolean sourceMatches(PolicyRule.Input in) {
        return in.proposal().quote().pair().source() == in.mandate().permissions().sourceCurrency()
                && in.account().goal().spent().currency() == in.mandate().permissions().sourceCurrency();
    }

    private static boolean targetMatches(PolicyRule.Input in) {
        return in.proposal().quote().pair().target() == in.mandate().permissions().targetCurrency()
                && in.account().goal().targetAmount().currency() == in.mandate().permissions().targetCurrency();
    }

    private static Money projectedSpend(PolicyRule.Input in) {
        return in.account().goal().spent()
                .plus(in.account().committedSpend())
                .plus(in.proposal().sourceAmount());
    }
}
