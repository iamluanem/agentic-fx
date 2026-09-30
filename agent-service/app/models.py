"""API and domain models. Everything the LLM produces is parsed into these models and validated; nothing else
is trusted."""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

Currency = Literal["BRL", "EUR", "USD", "GBP"]
ExecutionMode = Literal["HUMAN_IN_THE_LOOP", "AUTONOMOUS"]
StrategyType = Literal["TARGET_RATE", "ACCUMULATE", "DEADLINE_AWARE"]

AMOUNT_FIELDS = ("targetAmount", "maxBudget", "maxTransactionAmount", "approvalThreshold")
REQUIRED_FIELDS = ("targetCurrency", "targetAmount", "deadline", "maxBudget")


def _money(value: Decimal | None) -> Decimal | None:
    if value is None:
        return None
    if value <= 0:
        raise ValueError("amounts must be positive")
    if value > Decimal("1000000000"):
        raise ValueError("amount is unrealistically large")
    return value.quantize(Decimal("0.01"))


class StructuredIntent(BaseModel):
    """A (possibly partial) financial intent. Field names match the fx-api goal/mandate contract."""

    model_config = ConfigDict(extra="forbid")

    name: str | None = Field(default=None, max_length=120)
    sourceCurrency: Currency | None = None
    targetCurrency: Currency | None = None
    targetAmount: Decimal | None = None
    deadline: date | None = None
    maxBudget: Decimal | None = None
    targetRate: Decimal | None = None
    maxTransactionAmount: Decimal | None = None
    approvalThreshold: Decimal | None = None
    allowPartialExecution: bool | None = None
    executionMode: ExecutionMode | None = None
    strategyType: StrategyType | None = None

    @field_validator("targetAmount", "maxBudget", "maxTransactionAmount", "approvalThreshold")
    @classmethod
    def _amounts(cls, v: Decimal | None) -> Decimal | None:
        return _money(v)

    @field_validator("targetRate")
    @classmethod
    def _rate(cls, v: Decimal | None) -> Decimal | None:
        if v is None:
            return None
        if v <= 0 or v > Decimal("1000"):
            raise ValueError("rate out of range")
        return v.quantize(Decimal("0.00000001"))

    @field_validator("name")
    @classmethod
    def _name(cls, v: str | None) -> str | None:
        if v is None:
            return None
        cleaned = " ".join("".join(ch for ch in v if ch.isprintable()).split())
        return cleaned[:120] or None

    def merged_with(self, newer: "StructuredIntent") -> "StructuredIntent":
        """Fields stated in the newer turn override earlier ones; unstated fields are kept."""
        data = self.model_dump()
        data.update({k: v for k, v in newer.model_dump().items() if v is not None})
        return StructuredIntent.model_validate(data)


class AppliedDefault(BaseModel):
    field: str
    value: Any
    reason: str


class IntentRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    message: str = Field(min_length=1, max_length=2000)
    previousIntent: StructuredIntent | None = None
    awaitingFields: list[str] = Field(default_factory=list, max_length=10)


class IntentResponse(BaseModel):
    status: Literal["READY", "NEEDS_INPUT"]
    intent: StructuredIntent
    missing: list[str]
    questions: list[str]
    defaultsApplied: list[AppliedDefault]
    warnings: list[str]
    insights: list[str]
    reply: str
    parser: Literal["anthropic", "deterministic"]
    language: Literal["en", "pt"]


class AdviseRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    goalId: str = Field(pattern=r"^[0-9a-fA-F-]{36}$")
    message: str = Field(min_length=1, max_length=2000)
    requestId: str = Field(pattern=r"^[A-Za-z0-9-]{8,64}$")


class ToolCallRecord(BaseModel):
    tool: str
    arguments: dict[str, Any]
    ok: bool
    summary: str


class AdviseResponse(BaseModel):
    reply: str
    actions: list[ToolCallRecord]
    proposal: dict[str, Any] | None = None
    agent: Literal["anthropic", "deterministic"]
