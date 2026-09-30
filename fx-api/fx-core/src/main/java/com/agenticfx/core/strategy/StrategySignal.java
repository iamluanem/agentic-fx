package com.agenticfx.core.strategy;

import com.agenticfx.core.agent.AgentDecisionType;
import com.agenticfx.core.money.Money;

import java.util.Objects;

/** Output of the Strategy Engine: either a proposal to convert an amount, or a reason to wait. Never an execution. */
public sealed interface StrategySignal {

    AgentDecisionType type();

    String summary();

    record Propose(Money sourceAmount, String whatHappened, String why, String mandateRule) implements StrategySignal {
        public Propose {
            Objects.requireNonNull(sourceAmount, "sourceAmount");
            Objects.requireNonNull(whatHappened, "whatHappened");
            Objects.requireNonNull(why, "why");
            Objects.requireNonNull(mandateRule, "mandateRule");
        }

        @Override
        public AgentDecisionType type() {
            return AgentDecisionType.MARKET_CONDITION_DETECTED;
        }

        @Override
        public String summary() {
            return whatHappened;
        }
    }

    record Wait(AgentDecisionType type, String summary) implements StrategySignal {
        public Wait {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(summary, "summary");
        }
    }
}
