from datetime import date
from decimal import Decimal

import pytest

from app.intent.deterministic import detect_language, money_mentions, parse, parse_number

TODAY = date(2026, 9, 30)

SPEC_EXAMPLE = ("I need €5,000 by October 20. I have up to R$32,000 available. Try to get the best possible "
                "conversion. You may buy in multiple transactions. Never convert more than R$5,000 in one "
                "transaction, and ask me before any transaction above R$3,000.")


def test_spec_example_is_fully_understood():
    i = parse(SPEC_EXAMPLE, TODAY)
    assert (i.sourceCurrency, i.targetCurrency) == ("BRL", "EUR")
    assert i.targetAmount == Decimal("5000")
    assert i.deadline == date(2026, 10, 20)
    assert i.maxBudget == Decimal("32000")
    assert i.maxTransactionAmount == Decimal("5000")
    assert i.approvalThreshold == Decimal("3000")
    assert i.allowPartialExecution is True
    assert i.executionMode == "HUMAN_IN_THE_LOOP"


def test_second_spec_example():
    i = parse("I need €5,000 by October 20. I can spend up to R$32,000. Buy gradually but ask me before spending "
              "more than R$3,000.", TODAY)
    assert (i.targetAmount, i.maxBudget, i.approvalThreshold) == (Decimal(5000), Decimal(32000), Decimal(3000))
    assert i.maxTransactionAmount is None, "not stated, so not invented"
    assert i.strategyType == "ACCUMULATE"


def test_budget_after_target_in_same_sentence():
    i = parse("I need €5,000 before October 20 and don't want to spend more than R$32,000.", TODAY)
    assert (i.targetAmount, i.maxBudget) == (Decimal(5000), Decimal(32000))


def test_portuguese_with_brazilian_number_format():
    i = parse("Preciso de 5.000 euros para minha viagem à Europa até 20 de outubro. Posso gastar até R$ 32.000, "
              "nunca mais de R$ 5.000 por vez, e me pergunte antes de qualquer transação acima de R$ 3.000.", TODAY)
    assert i.name == "Viagem à Europa"
    assert (i.targetCurrency, i.targetAmount) == ("EUR", Decimal(5000))
    assert (i.maxBudget, i.maxTransactionAmount, i.approvalThreshold) == (Decimal(32000), Decimal(5000), Decimal(3000))
    assert i.deadline == date(2026, 10, 20)
    assert detect_language("Preciso de euros até outubro") == "pt"


def test_short_goal_only_extracts_what_is_there():
    i = parse("I need €5,000 for my Europe trip before October 20.", TODAY)
    assert i.name == "Europe trip"
    assert i.maxBudget is None and i.sourceCurrency is None


@pytest.mark.parametrize("raw,expected", [
    ("5,000", "5000"), ("5.000", "5000"), ("32.000,50", "32000.50"), ("32,000.50", "32000.50"),
    ("2.5", "2.5"), ("5k", "5000"), ("3 mil", "3000"), ("1,234,567", "1234567"),
])
def test_number_formats(raw, expected):
    assert parse_number(raw) == Decimal(expected)


def test_currency_symbols_do_not_double_count():
    found = money_mentions("R$32,000 and US$ 100 and £50 and 20 euros")
    assert [(m.currency, m.amount) for m in found] == [("BRL", 32000), ("USD", 100), ("GBP", 50), ("EUR", 20)]


@pytest.mark.parametrize("text,expected", [
    ("by October 20", date(2026, 10, 20)),
    ("before Oct 20th, 2027", date(2027, 10, 20)),
    ("até 20 de outubro", date(2026, 10, 20)),
    ("antes de 15/01", date(2027, 1, 15)),
    ("by 2026-12-01", date(2026, 12, 1)),
    ("by September 1", date(2027, 9, 1)),
])
def test_deadlines_resolve_to_next_occurrence(text, expected):
    assert parse(f"I need €100 {text}", TODAY).deadline == expected


def test_inverse_rate_quote_is_converted():
    i = parse("Buy USD 2k when the dollar reaches R$5,40, budget R$ 12.000, all at once, automatically", TODAY)
    assert i.targetRate == (Decimal(1) / Decimal("5.40")).quantize(Decimal("0.00000001"))
    assert i.strategyType == "TARGET_RATE"
    assert i.allowPartialExecution is False
    assert i.executionMode == "AUTONOMOUS"


def test_bare_answer_fills_the_awaited_field():
    assert parse("32000", TODAY, ["maxBudget"]).maxBudget == Decimal(32000)
    assert parse("R$ 32.000", TODAY, ["maxBudget"]).maxBudget == Decimal(32000)
    assert parse("32000", TODAY, []).maxBudget is None
