from fastapi.testclient import TestClient

from app.main import create_app
from tests.conftest import GOAL_ID, make_token


def app_client(fx):
    return TestClient(create_app(use_settings_provider=False, fx_transport=fx.transport()))


def test_health_reports_deterministic_mode(fx):
    assert app_client(fx).get("/health").json() == {"status": "UP", "llm": "deterministic"}


def test_requires_a_valid_customer_token(fx):
    c = app_client(fx)
    body = {"message": "I need €100 by October 20"}
    assert c.post("/agent/intents", json=body).status_code == 401
    assert c.post("/agent/intents", json=body, headers={"Authorization": "Bearer nonsense"}).status_code == 401
    expired = make_token(exp_in=-10)
    assert c.post("/agent/intents", json=body, headers={"Authorization": f"Bearer {expired}"}).status_code == 401
    agent = make_token(roles=("AGENT",))
    assert c.post("/agent/intents", json=body, headers={"Authorization": f"Bearer {agent}"}).status_code == 403
    wrong_issuer = make_token(issuer="evil")
    assert c.post("/agent/intents", json=body, headers={"Authorization": f"Bearer {wrong_issuer}"}).status_code == 401


def test_intent_endpoint_end_to_end(fx):
    r = app_client(fx).post("/agent/intents", headers={"Authorization": f"Bearer {make_token()}",
                                                      "X-Correlation-Id": "demo-correlation-1"},
                            json={"message": "I need €5,000 by October 20 and don't want to spend more than R$32,000."})
    assert r.status_code == 200
    assert r.headers["X-Correlation-Id"] == "demo-correlation-1"
    data = r.json()
    assert data["status"] == "READY"
    assert data["intent"]["targetAmount"] == "5000.00"
    assert data["insights"], "uses the live simulated rate from fx-api"
    assert ("GET", "/fx/rates") in [(m, p) for m, p, *_ in fx.requests]


def test_input_validation(fx):
    c = app_client(fx)
    auth = {"Authorization": f"Bearer {make_token()}"}
    assert c.post("/agent/intents", headers=auth, json={"message": "x" * 2001}).status_code == 422
    assert c.post("/agent/intents", headers=auth, json={"message": "hi", "role": "admin"}).status_code == 422
    assert c.post("/agent/advise", headers=auth,
                  json={"goalId": "not-a-uuid", "message": "hi", "requestId": "req-12345678"}).status_code == 422


def test_advise_endpoint(fx):
    r = app_client(fx).post("/agent/advise", headers={"Authorization": f"Bearer {make_token()}"},
                            json={"goalId": GOAL_ID, "message": "How is my goal going?", "requestId": "req-12345678"})
    assert r.status_code == 200 and r.json()["agent"] == "deterministic"
