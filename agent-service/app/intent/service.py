"""Orchestrates intent parsing: LLM (if configured) → Pydantic → grounding → deterministic validation.
Any LLM failure falls back to the rule-based parser, so the product keeps working without a model."""

from __future__ import annotations

import logging
from datetime import date
from decimal import Decimal
from typing import Callable

from app.intent import deterministic
from app.intent.llm import LlmIntentParser
from app.intent.validation import fmt, ground, market_insight, validate
from app.llm.provider import LlmError
from app.models import IntentRequest, IntentResponse, StructuredIntent

log = logging.getLogger(__name__)

RateLookup = Callable[[str, str], Decimal | None]


class IntentService:
    def __init__(self, llm_parser: LlmIntentParser | None, fee_rate: Decimal = Decimal("0.006")):
        self._llm = llm_parser
        self._fee_rate = fee_rate

    def interpret(self, request: IntentRequest, today: date, rate_lookup: RateLookup | None = None) -> IntentResponse:
        previous = request.previousIntent or StructuredIntent()
        language = deterministic.detect_language(request.message)
        warnings: list[str] = []
        parser = "deterministic"
        extracted: StructuredIntent | None = None

        if self._llm is not None:
            try:
                extracted = self._llm.parse(request.message, today, request.previousIntent, request.awaitingFields)
                extracted, dropped = ground(extracted, previous, request.message)
                warnings += dropped
                parser = "anthropic"
            except LlmError as e:
                log.warning("LLM intent parsing failed, using deterministic parser: %s", e)
                warnings.append("The AI parser was unavailable; used the rule-based parser.")
                extracted = None
        if extracted is None:
            extracted = deterministic.parse(request.message, today, request.awaitingFields)

        merged = previous.merged_with(extracted)
        result = validate(merged, today, language)
        warnings += result.warnings

        rate = None
        if rate_lookup and result.intent.sourceCurrency and result.intent.targetCurrency:
            try:
                rate = rate_lookup(result.intent.sourceCurrency, result.intent.targetCurrency)
            except Exception as e:  # insight is best-effort
                log.info("Rate lookup failed: %s", e)
        insights = market_insight(result.intent, rate, self._fee_rate, language)

        status = "NEEDS_INPUT" if result.missing else "READY"
        return IntentResponse(status=status, intent=result.intent, missing=result.missing, questions=result.questions,
                              defaultsApplied=result.defaults, warnings=warnings, insights=insights,
                              reply=_reply(result.intent, result.questions, language), parser=parser,
                              language=language)


def _reply(intent: StructuredIntent, questions: list[str], language: str) -> str:
    if questions:
        lead = "Entendi até aqui. Só preciso saber:" if language == "pt" else "Got it so far. I just need to know:"
        return lead + " " + " ".join(questions)
    target = fmt(intent.targetAmount, intent.targetCurrency)
    budget = fmt(intent.maxBudget, intent.sourceCurrency)
    if language == "pt":
        return (f"Proposta de mandato: adquirir {target} até {intent.deadline:%d/%m/%Y}, gastando no máximo {budget}. "
                "Revise os termos abaixo; nada acontece até você ativar o agente.")
    return (f"Here is the proposed mandate: acquire {target} by {intent.deadline:%b %d, %Y}, spending at most {budget}. "
            "Review the terms below; nothing happens until you activate the agent.")
