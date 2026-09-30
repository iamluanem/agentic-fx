import json

import httpx
import pytest

from app.agent.advisor import Advisor
from app.agent.fx_client import FxApiClient
from app.agent.tools import FORBIDDEN_TOOL_WORDS, Session, build_tools, invoke
from app.llm.provider import LlmError, LlmTurn
from app.models import AdviseRequest
from tests.conftest import GOAL_ID, FakeFxApi, ScriptedProvider, tool_turn

REQUEST = AdviseRequest(goalId=GOAL_ID, message="Should I buy some euros now?", requestId="req-12345678")


def client(fx: FakeFxApi) -> FxApiClient:
    return FxApiClient("http://fx-api", "user-token", transport=fx.transport())


def test_registry_exposes_exactly_the_controlled_tools():
    names = set(build_tools())
    assert names == {"get_balances", "get_fx_quote", "get_goal", "get_goal_progress", "create_execution_proposal",
                     "request_user_approval", "get_transaction_status"}
    assert not any(w in n for n in names for w in FORBIDDEN_TOOL_WORDS)


def test_tools_use_the_delegated_agent_token_never_the_user_token(fx):
    tools, session = build_tools(), Session(GOAL_ID, "req-12345678")
    invoke(tools, client(fx), session, "get_balances", {})
    tool_calls = [r for r in fx.requests if r[1].startswith("/agent/tools")]
    assert tool_calls and all(r[2] == "Bearer agent-token-xyz" for r in tool_calls)
    assert fx.requests[0][1] == "/auth/delegate" and fx.requests[0][2] == "Bearer user-token"


def test_tools_are_scoped_to_the_session_goal(fx):
    tools, session = build_tools(), Session(GOAL_ID, "req-12345678")
    content, error = invoke(tools, client(fx), session, "get_goal",
                            {"goal_id": "22222222-2222-2222-2222-222222222222"})
    assert error and json.loads(content)["error"] == "TOOL_POLICY"
    assert not [r for r in fx.requests if "2222" in r[1]]


def test_only_one_proposal_per_turn_with_deterministic_idempotency_key(fx):
    tools, session = build_tools(), Session(GOAL_ID, "req-12345678")
    args = {"goal_id": GOAL_ID, "source_amount": 1000, "rationale": "test"}
    _, e1 = invoke(tools, client(fx), session, "create_execution_proposal", args)
    content, e2 = invoke(tools, client(fx), session, "request_user_approval", args)
    assert not e1 and e2 and "Only one proposal" in content
    posts = fx.tool_posts()
    assert len(posts) == 1 and posts[0][3] == "req-12345678-p1"


def test_llm_loop_proposes_and_reports_policy_outcome(fx):
    fx.decision = "REQUIRE_APPROVAL"
    provider = ScriptedProvider([
        tool_turn("get_goal", {"goal_id": GOAL_ID}),
        tool_turn("create_execution_proposal", {"goal_id": GOAL_ID, "source_amount": 3500,
                                                "rationale": "EUR improved 1.9% vs reference"}),
        LlmTurn(text="I proposed converting R$3,500; it needs your approval because it is above R$2,500."),
    ])
    r = Advisor(provider).advise(REQUEST, client(fx))
    assert r.agent == "anthropic"
    assert [a.tool for a in r.actions] == ["get_goal", "create_execution_proposal"]
    assert r.proposal["policy"]["decision"] == "REQUIRE_APPROVAL"
    body = json.loads(fx.tool_posts()[0][4])
    assert body == {"goalId": GOAL_ID, "sourceAmount": "3500.00", "rationale": "EUR improved 1.9% vs reference",
                    "marketContext": None, "requireApproval": False}
    tool_results = provider.calls[2]["messages"][-1].tool_results
    assert tool_results[0][2] is False


def test_model_cannot_call_tools_that_do_not_exist(fx):
    provider = ScriptedProvider([
        tool_turn("execute_conversion", {"amount": 30000}),
        LlmTurn(text="I cannot execute conversions directly."),
    ])
    r = Advisor(provider).advise(REQUEST, client(fx))
    assert r.actions[0].ok is False and "Unknown tool" in r.actions[0].summary
    assert fx.tool_posts() == []


def test_llm_failure_after_a_proposal_never_creates_a_second_one(fx):
    provider = ScriptedProvider([
        tool_turn("create_execution_proposal", {"goal_id": GOAL_ID, "source_amount": 1000, "rationale": "x"}),
        LlmError("timeout"),
    ])
    r = Advisor(provider).advise(REQUEST, client(fx))
    assert len(fx.tool_posts()) == 1
    assert "allowed by policy" in r.reply


def test_large_tool_results_are_parsed_whole_and_clipped_only_for_the_model(fx):
    original = fx.handler

    def big(request):
        response = original(request)
        if request.url.path == f"/agent/tools/goals/{GOAL_ID}":
            data = response.json()
            data["recentDecisions"] = [{"summary": "x" * 200} for _ in range(50)]
            return httpx.Response(200, json=data)
        return response
    fx.handler = big
    r = Advisor(None).advise(REQUEST, client(fx))
    assert "€ 331.30 / € 3,000.00" in r.reply
    provider = ScriptedProvider([tool_turn("get_goal", {"goal_id": GOAL_ID}), LlmTurn(text="ok")])
    Advisor(provider).advise(REQUEST, client(fx))
    sent = provider.calls[1]["messages"][-1].tool_results[0][1]
    assert len(sent) < 4100 and sent.endswith("chars]")


def test_deterministic_advisor_summarises_without_acting(fx):
    r = Advisor(None).advise(REQUEST, client(fx))
    assert r.agent == "deterministic" and r.proposal is None
    assert "€ 331.30 / € 3,000.00" in r.reply and fx.tool_posts() == []


@pytest.mark.parametrize("message,require_approval", [
    ("Convert R$1,000 now please", False),
    ("Converta R$ 1.000 agora, mas me pergunte antes", True),
])
def test_deterministic_advisor_acts_only_on_explicit_request(fx, message, require_approval):
    r = Advisor(None).advise(AdviseRequest(goalId=GOAL_ID, message=message, requestId="req-abcdefgh"), client(fx))
    body = json.loads(fx.tool_posts()[0][4])
    assert body["sourceAmount"] == "1000.00" and body["requireApproval"] is require_approval
    assert r.proposal is not None
