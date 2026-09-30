from datetime import date
from decimal import Decimal

from app.intent.validation import ground, market_insight, validate
from app.models import StructuredIntent

TODAY = date(2026, 9, 30)


def full(**over) -> StructuredIntent:
    base = dict(sourceCurrency="BRL", targetCurrency="EUR", targetAmount=Decimal(5000), deadline=date(2026, 10, 20),
                maxBudget=Decimal(32000))
    base.update(over)
    return StructuredIntent(**base)


def test_complete_intent_gets_visible_safe_defaults():
    v = validate(full(), TODAY, "en")
    assert v.missing == []
    assert v.intent.maxTransactionAmount == Decimal(8000)
    assert v.intent.executionMode == "HUMAN_IN_THE_LOOP"
    assert v.intent.allowPartialExecution is True
    assert v.intent.strategyType == "ACCUMULATE"
    assert {d.field for d in v.defaults} == {"maxTransactionAmount", "executionMode", "allowPartialExecution",
                                             "strategyType"}


def test_only_missing_essentials_are_asked():
    v = validate(StructuredIntent(targetCurrency="EUR", targetAmount=Decimal(5000), deadline=date(2026, 10, 20)),
                 TODAY, "pt")
    assert v.missing == ["maxBudget"]
    assert v.questions == ["Qual o máximo que você aceita gastar em BRL?"]
    assert v.intent.sourceCurrency == "BRL"


def test_inconsistent_limits_are_fixed_and_reported():
    v = validate(full(maxTransactionAmount=Decimal(50000), approvalThreshold=Decimal(40000)), TODAY, "en")
    assert v.intent.maxTransactionAmount == Decimal(32000)
    assert any("capped" in w for w in v.warnings)
    assert any("ever need your approval" in w for w in v.warnings)


def test_past_deadline_and_same_currency_are_rejected():
    v = validate(full(deadline=date(2026, 9, 1), targetCurrency="BRL"), TODAY, "en")
    assert set(v.missing) == {"deadline", "targetCurrency"}
    assert len(v.warnings) == 2


def test_grounding_drops_amounts_the_user_never_wrote():
    hallucinated = full(maxBudget=Decimal(40000), maxTransactionAmount=Decimal(5000))
    grounded, warnings = ground(hallucinated, StructuredIntent(), "I need €5,000 by October 20 with R$32.000")
    assert grounded.maxBudget is None
    assert grounded.maxTransactionAmount == Decimal(5000)
    assert warnings == ["Ignored maxBudget: the value was not stated in your message."]


def test_grounding_keeps_values_from_previous_turns():
    previous = full()
    grounded, warnings = ground(full(approvalThreshold=Decimal(3000)), previous, "ask me above 3000")
    assert grounded.maxBudget == Decimal(32000) and grounded.approvalThreshold == Decimal(3000)
    assert warnings == []


def test_market_insight_compares_budget_with_cost():
    ok = market_insight(full(), Decimal("0.165"), Decimal("0.006"), "en")[0]
    assert "30,486" in ok and "margin" in ok
    short = market_insight(full(maxBudget=Decimal(29000)), Decimal("0.165"), Decimal("0.006"), "pt")[0]
    assert "abaixo" in short
