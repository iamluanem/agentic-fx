"""The agent's tool surface. Deliberately closed: there is no execute/approve/convert tool, and the registry
refuses to register one. Every call is scoped to the goal of the current session and at most one proposal can be
created per session; the Policy Engine in fx-api decides what (if anything) happens to it."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation
from typing import Any, Callable

from app.agent.fx_client import FxApiClient, FxApiError
from app.llm.provider import ToolSpec
from app.models import ToolCallRecord

FORBIDDEN_TOOL_WORDS = ("execute", "approve", "convert", "transfer", "payment", "withdraw")
MAX_RESULT_CHARS = 4000


class ToolPolicyError(ValueError):
    pass


@dataclass
class Session:
    goal_id: str
    request_id: str
    proposals_created: int = 0
    last_proposal: dict[str, Any] | None = None
    records: list[ToolCallRecord] = field(default_factory=list)


@dataclass(frozen=True)
class Tool:
    spec: ToolSpec
    run: Callable[[FxApiClient, Session, dict[str, Any]], Any]


_GOAL = {"goal_id": {"type": "string", "description": "The goal id of this session"}}
_PROPOSAL_INPUT = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        **_GOAL,
        "source_amount": {"type": "number", "exclusiveMinimum": 0,
                          "description": "Amount of the goal's SOURCE currency to convert"},
        "rationale": {"type": "string", "maxLength": 500, "description": "Why this conversion, in plain words"},
        "market_context": {"type": "string", "maxLength": 500, "description": "What happened in the market"},
    },
    "required": ["goal_id", "source_amount", "rationale"],
}


def _scoped(session: Session, args: dict[str, Any]) -> str:
    goal_id = str(args.get("goal_id") or session.goal_id)
    if goal_id != session.goal_id:
        raise ToolPolicyError("This session may only act on goal " + session.goal_id)
    return goal_id


def _amount(args: dict[str, Any]) -> Decimal:
    try:
        value = Decimal(str(args["source_amount"])).quantize(Decimal("0.01"))
    except (KeyError, InvalidOperation) as e:
        raise ToolPolicyError("source_amount must be a number") from e
    if value <= 0:
        raise ToolPolicyError("source_amount must be positive")
    return value


def _propose(require_approval: bool) -> Callable[[FxApiClient, Session, dict[str, Any]], Any]:
    def run(client: FxApiClient, session: Session, args: dict[str, Any]) -> Any:
        goal_id = _scoped(session, args)
        if session.proposals_created >= 1:
            raise ToolPolicyError("Only one proposal may be created per conversation turn")
        body = {
            "goalId": goal_id,
            "sourceAmount": str(_amount(args)),
            "rationale": str(args.get("rationale", ""))[:500],
            "marketContext": str(args.get("market_context", ""))[:500] or None,
            "requireApproval": require_approval,
        }
        session.proposals_created += 1
        result = client.agent_propose(body, idempotency_key=f"{session.request_id}-p{session.proposals_created}")
        session.last_proposal = result
        return result
    return run


def build_tools() -> dict[str, Tool]:
    tools = [
        Tool(ToolSpec("get_balances", "Available balances per currency.",
                      {"type": "object", "properties": {}, "additionalProperties": False}),
             lambda c, s, a: c.agent_get("/balances")),
        Tool(ToolSpec("get_fx_quote", "Indicative quote (rate, fee, amount received). Not executable.",
                      {"type": "object", "additionalProperties": False,
                       "properties": {"source": {"enum": ["BRL", "EUR", "USD", "GBP"]},
                                      "target": {"enum": ["BRL", "EUR", "USD", "GBP"]},
                                      "amount": {"type": "number", "exclusiveMinimum": 0}},
                       "required": ["source", "target", "amount"]}),
             lambda c, s, a: c.agent_get("/quote", {"source": a["source"], "target": a["target"],
                                                     "amount": str(a["amount"])})),
        Tool(ToolSpec("get_goal", "Goal, active mandate (the limits you must respect), agent status, open proposal "
                      "and recent conversions.",
                      {"type": "object", "properties": _GOAL, "additionalProperties": False}),
             lambda c, s, a: c.agent_get(f"/goals/{_scoped(s, a)}")),
        Tool(ToolSpec("get_goal_progress", "Acquired vs target, spent vs budget, deadline.",
                      {"type": "object", "properties": _GOAL, "additionalProperties": False}),
             lambda c, s, a: c.agent_get(f"/goals/{_scoped(s, a)}/progress")),
        Tool(ToolSpec("create_execution_proposal",
                      "Propose converting an amount for this goal. This does NOT execute: the platform's policy "
                      "engine decides to allow, deny, or ask the customer. At most one per turn.",
                      _PROPOSAL_INPUT),
             _propose(require_approval=False)),
        Tool(ToolSpec("request_user_approval",
                      "Propose a conversion and route it to the customer for approval even if the mandate would "
                      "allow it automatically. Use when unsure.",
                      _PROPOSAL_INPUT),
             _propose(require_approval=True)),
        Tool(ToolSpec("get_transaction_status", "Status of a proposal created earlier (policy decision, approval, "
                      "conversion).",
                      {"type": "object", "additionalProperties": False,
                       "properties": {"proposal_id": {"type": "string", "pattern": "^[0-9a-fA-F-]{36}$"}},
                       "required": ["proposal_id"]}),
             lambda c, s, a: c.agent_get(f"/proposals/{a['proposal_id']}")),
    ]
    registry = {}
    for tool in tools:
        if any(word in tool.spec.name for word in FORBIDDEN_TOOL_WORDS):
            raise ToolPolicyError(f"Tool {tool.spec.name} would give the model money-moving capability")
        registry[tool.spec.name] = tool
    return registry


def invoke(tools: dict[str, Tool], client: FxApiClient, session: Session, name: str,
           args: dict[str, Any]) -> tuple[str, bool]:
    """Runs a tool and returns (JSON content, is_error). Use {for_model} before sending content to an LLM."""
    tool = tools.get(name)
    if tool is None:
        content, ok = json.dumps({"error": f"Unknown tool {name}"}), False
    else:
        try:
            content, ok = json.dumps(tool.run(client, session, args), default=str), True
        except ToolPolicyError as e:
            content, ok = json.dumps({"error": "TOOL_POLICY", "detail": str(e)}), False
        except FxApiError as e:
            content, ok = json.dumps({"error": e.code, "detail": e.detail}), False
        except (KeyError, TypeError) as e:
            content, ok = json.dumps({"error": "BAD_ARGUMENTS", "detail": str(e)}), False
    session.records.append(ToolCallRecord(tool=name, arguments=args, ok=ok, summary=_summary(name, content, ok)))
    return content, not ok


def for_model(content: str) -> str:
    """Bounds what goes into the model's context; the full result stays available to code."""
    if len(content) <= MAX_RESULT_CHARS:
        return content
    return content[:MAX_RESULT_CHARS] + f"... [truncated {len(content) - MAX_RESULT_CHARS} chars]"


def _summary(name: str, content: str, ok: bool) -> str:
    if not ok:
        return content[:200]
    if name in ("create_execution_proposal", "request_user_approval"):
        data = json.loads(content)
        policy = (data.get("policy") or {}).get("decision")
        status = (data.get("proposal") or {}).get("status")
        return f"proposal {status}, policy {policy}"
    return f"{len(content)} bytes of data"
