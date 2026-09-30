"""Deterministic post-processing applied to every intent, whatever produced it (LLM or rules).

- fills safe defaults and reports each one (the user sees and can edit them before activating)
- asks only for what is genuinely missing
- enforces consistency (limits, currencies, dates)
- grounds LLM output: amounts the user never wrote are discarded
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, timedelta
from decimal import ROUND_DOWN, ROUND_UP, Decimal

from app.intent.deterministic import numbers_in
from app.models import AMOUNT_FIELDS, REQUIRED_FIELDS, AppliedDefault, StructuredIntent

SYMBOLS = {"BRL": "R$", "EUR": "€", "USD": "$", "GBP": "£"}

QUESTIONS = {
    "en": {
        "targetCurrency": "Which currency do you want to acquire (EUR, USD, GBP or BRL)?",
        "targetAmount": "How much {target} do you need?",
        "deadline": "By when do you need it?",
        "maxBudget": "What is the most you are willing to spend in {source}?",
    },
    "pt": {
        "targetCurrency": "Qual moeda você quer adquirir (EUR, USD, GBP ou BRL)?",
        "targetAmount": "Quanto de {target} você precisa?",
        "deadline": "Até quando você precisa?",
        "maxBudget": "Qual o máximo que você aceita gastar em {source}?",
    },
}


def fmt(amount: Decimal | None, currency: str | None) -> str:
    if amount is None:
        return "?"
    return f"{SYMBOLS.get(currency or '', '')}{amount:,.2f}".replace(".00", "")


@dataclass
class Validation:
    intent: StructuredIntent
    missing: list[str] = field(default_factory=list)
    questions: list[str] = field(default_factory=list)
    defaults: list[AppliedDefault] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


def ground(candidate: StructuredIntent, previous: StructuredIntent, user_text: str) -> tuple[StructuredIntent, list[str]]:
    """Drops amounts introduced this turn that do not appear anywhere in what the user wrote."""
    stated = numbers_in(user_text)
    data = candidate.model_dump()
    dropped = []
    for name in (*AMOUNT_FIELDS, "targetRate"):
        value = data.get(name)
        if value is None or value == getattr(previous, name):
            continue
        if name == "targetRate":
            inverse = (Decimal(1) / value).quantize(Decimal("0.0001"))
            ok = any(abs(n - value) < Decimal("0.00001") or abs(n - inverse) < Decimal("0.0005") for n in stated)
        else:
            ok = value.normalize() in stated or (value / 1000).normalize() in stated
        if not ok:
            data[name] = None
            dropped.append(name)
    warnings = [f"Ignored {name}: the value was not stated in your message." for name in dropped]
    return StructuredIntent.model_validate(data), warnings


def validate(intent: StructuredIntent, today: date, language: str) -> Validation:
    data = intent.model_dump()
    v = Validation(intent=intent)
    lang = QUESTIONS[language]

    if data["sourceCurrency"] is None and data["targetCurrency"] not in (None, "BRL"):
        data["sourceCurrency"] = "BRL"
        v.defaults.append(AppliedDefault(field="sourceCurrency", value="BRL",
                                         reason="Paying from your BRL balance (main account currency)."))
    if data["sourceCurrency"] and data["sourceCurrency"] == data["targetCurrency"]:
        v.warnings.append("Source and target currency are the same; please choose a different target currency.")
        data["targetCurrency"] = None

    if data["deadline"] is not None:
        if data["deadline"] < today:
            v.warnings.append(f"The deadline {data['deadline']} is in the past.")
            data["deadline"] = None
        elif data["deadline"] > today + timedelta(days=730):
            v.warnings.append("Deadlines more than two years out are not supported.")
            data["deadline"] = None

    budget = data["maxBudget"]
    if budget is not None:
        if data["maxTransactionAmount"] is None:
            suggested = max(Decimal(100), (budget / 4 / 100).to_integral_value(ROUND_DOWN) * 100).min(budget)
            data["maxTransactionAmount"] = suggested
            v.defaults.append(AppliedDefault(field="maxTransactionAmount", value=str(suggested),
                                             reason="A quarter of the budget per conversion, so the goal is built in several steps."))
        elif data["maxTransactionAmount"] > budget:
            v.warnings.append("The per-transaction limit was above the total budget; it was capped at the budget.")
            data["maxTransactionAmount"] = budget
    threshold = data["approvalThreshold"]
    if threshold is not None and data["maxTransactionAmount"] is not None and threshold >= data["maxTransactionAmount"]:
        v.warnings.append("The approval threshold is at or above the per-transaction limit, so no conversion will ever "
                          "need your approval.")

    if data["executionMode"] is None:
        data["executionMode"] = "HUMAN_IN_THE_LOOP"
        v.defaults.append(AppliedDefault(
            field="executionMode", value="HUMAN_IN_THE_LOOP",
            reason="Safest default: " + ("you approve conversions above your threshold."
                                         if threshold is not None else "you approve every conversion.")))
    if data["allowPartialExecution"] is None:
        data["allowPartialExecution"] = True
        v.defaults.append(AppliedDefault(field="allowPartialExecution", value=True,
                                         reason="Buying in several conversions lets the agent use good moments."))
    if data["strategyType"] is None:
        data["strategyType"] = "TARGET_RATE" if data["targetRate"] else "ACCUMULATE"
        v.defaults.append(AppliedDefault(field="strategyType", value=data["strategyType"],
                                         reason="Progressive accumulation: buy on favourable moves until the deadline."))
    if data["strategyType"] == "TARGET_RATE" and data["targetRate"] is None:
        v.warnings.append("A target-rate strategy needs a target rate; switched to progressive accumulation.")
        data["strategyType"] = "ACCUMULATE"
    if data["name"] is None and data["targetCurrency"]:
        data["name"] = f"{data['targetCurrency']} goal"

    for name in REQUIRED_FIELDS:
        if data[name] is None:
            v.missing.append(name)
            v.questions.append(lang[name].format(target=data["targetCurrency"] or "the currency",
                                                 source=data["sourceCurrency"] or "BRL"))
    v.intent = StructuredIntent.model_validate(data)
    return v


def market_insight(intent: StructuredIntent, rate: Decimal | None, fee_rate: Decimal, language: str) -> list[str]:
    """How the budget compares with what the goal costs at the current simulated rate."""
    if rate is None or intent.targetAmount is None or intent.maxBudget is None:
        return []
    cost = (intent.targetAmount / (rate * (1 - fee_rate))).quantize(Decimal("1"), rounding=ROUND_UP)
    margin = (intent.maxBudget - cost) / cost * 100
    src, tgt = intent.sourceCurrency or "BRL", intent.targetCurrency
    if language == "pt":
        base = (f"Na cotação simulada atual, {fmt(intent.targetAmount, tgt)} custaria cerca de {fmt(cost, src)} "
                f"com taxas.")
        tail = (f" Seu orçamento deixa uma margem de {margin:.1f}%." if margin >= 0 else
                f" Seu orçamento está {abs(margin):.1f}% abaixo disso: o agente só conseguirá concluir se o câmbio melhorar.")
    else:
        base = f"At the current simulated rate, {fmt(intent.targetAmount, tgt)} would cost about {fmt(cost, src)} including fees."
        tail = (f" Your budget leaves a {margin:.1f}% margin." if margin >= 0 else
                f" Your budget is {abs(margin):.1f}% short of that: the agent can only finish if the rate improves.")
    return [base + tail]
