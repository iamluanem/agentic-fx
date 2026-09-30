import json
import os
import time
import uuid

import httpx
import jwt
import pytest

os.environ.setdefault("JWT_SECRET", "test-secret-that-is-at-least-32-bytes-long!!")
os.environ["LLM_PROVIDER"] = "none"

from app.llm.provider import LlmError, LlmTurn, ToolCall  # noqa: E402

SECRET = os.environ["JWT_SECRET"]
USER_ID = "00000000-0000-0000-0000-00000000d3a0"
GOAL_ID = "11111111-1111-1111-1111-111111111111"


def make_token(roles=("CUSTOMER",), sub=USER_ID, exp_in=600, issuer="agentic-fx") -> str:
    now = int(time.time())
    return jwt.encode({"sub": sub, "roles": list(roles), "iss": issuer, "iat": now, "exp": now + exp_in},
                      SECRET, algorithm="HS256")


class ScriptedProvider:
    """Fake LLM: returns the scripted turns in order and records what it was sent."""

    name = "fake"

    def __init__(self, turns):
        self.turns = list(turns)
        self.calls = []

    def complete(self, system, messages, tools, force_tool=None, max_tokens=1024):
        self.calls.append({"system": system, "messages": messages, "tools": [t.name for t in tools],
                           "force_tool": force_tool})
        if not self.turns:
            raise LlmError("script exhausted")
        turn = self.turns.pop(0)
        if isinstance(turn, Exception):
            raise turn
        return turn


def tool_turn(name, args, text="", call_id=None):
    return LlmTurn(text=text, tool_calls=[ToolCall(id=call_id or f"call-{uuid.uuid4().hex[:6]}", name=name,
                                                   arguments=args)])


class FakeFxApi:
    """Minimal fx-api: checks tokens the way the real RBAC does and records requests."""

    def __init__(self, decision="ALLOW"):
        self.decision = decision
        self.requests = []
        self.agent_token = "agent-token-xyz"

    def handler(self, request: httpx.Request) -> httpx.Response:
        auth = request.headers.get("Authorization", "")
        self.requests.append((request.method, request.url.path, auth, request.headers.get("Idempotency-Key"),
                              request.content.decode() if request.content else None))
        path = request.url.path
        if path == "/auth/delegate":
            return httpx.Response(200, json={"accessToken": self.agent_token, "roles": ["AGENT"]})
        if path == "/fx/rates":
            return httpx.Response(200, json=[{"pair": "BRL_EUR", "rate": 0.165}, {"pair": "BRL_USD", "rate": 0.18}])
        if path.startswith("/agent/tools") and auth != f"Bearer {self.agent_token}":
            return httpx.Response(403, json={"code": "FORBIDDEN", "detail": "agent token required"})
        if path == f"/agent/tools/goals/{GOAL_ID}":
            return httpx.Response(200, json={
                "goal": {"id": GOAL_ID, "name": "Europe Trip", "sourceCurrency": "BRL", "targetCurrency": "EUR",
                         "acquired": {"display": "€ 331.30"}, "targetAmount": {"display": "€ 3,000.00"},
                         "progress": 0.1104, "deadline": "2026-10-20"},
                "agent": {"state": "MONITORING", "message": "Waiting for EUR/BRL conditions to improve."},
                "mandate": {"version": 1}})
        if path.startswith("/agent/tools/goals/"):
            return httpx.Response(404, json={"code": "NOT_FOUND", "detail": "Goal not found"})
        if path == "/agent/tools/balances":
            return httpx.Response(200, json=[{"currency": "BRL", "available": {"display": "R$ 28,000.00"}}])
        if path == "/agent/tools/proposals" and request.method == "POST":
            body = json.loads(request.content)
            status = {"ALLOW": "COMPLETED", "REQUIRE_APPROVAL": "AWAITING_APPROVAL", "DENY": "REJECTED"}[self.decision]
            return httpx.Response(201, json={
                "proposal": {"id": str(uuid.uuid4()), "status": status,
                             "quote": {"sourceAmount": {"display": f"R$ {body['sourceAmount']}"},
                                       "targetAmount": {"display": "€ 164.01"}}},
                "policy": {"decision": self.decision, "reasonCodes": [], "explanation": "Policy explanation."}})
        return httpx.Response(404, json={"code": "NOT_FOUND", "detail": path})

    def transport(self):
        return httpx.MockTransport(self.handler)

    def tool_posts(self):
        return [r for r in self.requests if r[0] == "POST" and r[1] == "/agent/tools/proposals"]


@pytest.fixture
def fx():
    return FakeFxApi()
