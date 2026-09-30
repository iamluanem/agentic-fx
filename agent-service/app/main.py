from __future__ import annotations

import json
import logging
import re
import sys
import time
import uuid
from contextvars import ContextVar
from datetime import datetime
from zoneinfo import ZoneInfo

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse

from app.agent.advisor import Advisor
from app.agent.fx_client import FxApiClient, FxApiError
from app.config import Settings, get_settings
from app.intent.llm import LlmIntentParser
from app.intent.service import IntentService
from app.llm.anthropic_provider import AnthropicProvider
from app.llm.provider import LlmProvider
from app.models import AdviseRequest, AdviseResponse, IntentRequest, IntentResponse
from app.security import Caller, current_customer

correlation_id: ContextVar[str] = ContextVar("correlation_id", default="-")
_SAFE_ID = re.compile(r"^[A-Za-z0-9-]{8,64}$")


class JsonFormatter(logging.Formatter):
    """Structured logs. Messages never contain tokens or the customer's free text."""

    def format(self, record: logging.LogRecord) -> str:
        return json.dumps({"ts": self.formatTime(record), "level": record.levelname, "logger": record.name,
                           "msg": record.getMessage(), "correlationId": correlation_id.get(),
                           "service": "agent-service"})


def _configure_logging() -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())
    logging.basicConfig(level=logging.INFO, handlers=[handler], force=True)


def build_provider(settings: Settings) -> LlmProvider | None:
    if not settings.llm_enabled():
        return None
    return AnthropicProvider(settings.anthropic_api_key, settings.anthropic_model, settings.llm_timeout_seconds)


def create_app(provider: LlmProvider | None = None, use_settings_provider: bool = True,
               fx_transport=None) -> FastAPI:
    _configure_logging()
    settings = get_settings()
    llm = provider if provider is not None else (build_provider(settings) if use_settings_provider else None)
    intents = IntentService(LlmIntentParser(llm) if llm else None)
    advisor = Advisor(llm, max_turns=settings.agent_max_turns)
    log = logging.getLogger("agent-service")

    app = FastAPI(title="Agentic FX · agent-service", version="0.1.0",
                  description="Turns natural language into validated FX intents and runs the advisory agent. "
                              "It can propose; only fx-api's Policy Engine can authorise.")

    @app.middleware("http")
    async def correlation(request: Request, call_next):
        incoming = request.headers.get("X-Correlation-Id", "")
        cid = incoming if _SAFE_ID.match(incoming) else str(uuid.uuid4())
        token = correlation_id.set(cid)
        started = time.perf_counter()
        try:
            response = await call_next(request)
        finally:
            correlation_id.reset(token)
        response.headers["X-Correlation-Id"] = cid
        log.info("%s %s -> %s in %.0f ms", request.method, request.url.path, response.status_code,
                 (time.perf_counter() - started) * 1000)
        return response

    @app.exception_handler(FxApiError)
    async def fx_error(_: Request, e: FxApiError):
        return JSONResponse(status_code=502 if e.status >= 500 else e.status,
                            content={"code": e.code, "detail": e.detail})

    def client_for(caller: Caller, request: Request) -> FxApiClient:
        return FxApiClient(settings.fx_api_url, caller.token, request.headers.get("X-Correlation-Id"),
                           transport=fx_transport)

    @app.get("/health")
    def health() -> dict:
        return {"status": "UP", "llm": "anthropic" if llm else "deterministic"}

    @app.post("/agent/intents", response_model=IntentResponse)
    def interpret(body: IntentRequest, request: Request, caller: Caller = Depends(current_customer)) -> IntentResponse:
        today = datetime.now(ZoneInfo(settings.business_timezone)).date()
        with client_for(caller, request) as fx:
            def rate_lookup(source: str, target: str):
                return fx.rate(source, target)
            return intents.interpret(body, today, rate_lookup)

    @app.post("/agent/advise", response_model=AdviseResponse)
    def advise(body: AdviseRequest, request: Request, caller: Caller = Depends(current_customer)) -> AdviseResponse:
        if len(body.message) > settings.max_message_chars:
            raise HTTPException(413, "Message too long")
        with client_for(caller, request) as fx:
            return advisor.advise(body, fx)

    return app


app = create_app()
