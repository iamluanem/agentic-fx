"""HTTP client for fx-api. The agent never uses the customer's own token for tool calls: it exchanges it for a
short-lived AGENT token that fx-api only accepts on read endpoints and on proposal creation."""

from __future__ import annotations

from decimal import Decimal
from typing import Any

import httpx


class FxApiError(RuntimeError):
    def __init__(self, status: int, code: str, detail: str):
        super().__init__(f"{status} {code}: {detail}")
        self.status, self.code, self.detail = status, code, detail


class FxApiClient:
    def __init__(self, base_url: str, user_token: str, correlation_id: str | None = None,
                 transport: httpx.BaseTransport | None = None, timeout: float = 10.0):
        headers = {"X-Correlation-Id": correlation_id} if correlation_id else {}
        self._http = httpx.Client(base_url=base_url, headers=headers, timeout=timeout, transport=transport)
        self._user_token = user_token
        self._agent_token: str | None = None

    def close(self) -> None:
        self._http.close()

    def __enter__(self) -> "FxApiClient":
        return self

    def __exit__(self, *exc) -> None:
        self.close()

    # -------------------------------------------------------------- user-scoped (read-only market data)

    def rate(self, source: str, target: str) -> Decimal | None:
        rates = self._request("GET", "/fx/rates", token=self._user_token)
        for r in rates:
            pair = r["pair"]
            if pair == f"{source}_{target}":
                return Decimal(str(r["rate"]))
            if pair == f"{target}_{source}":
                return Decimal(1) / Decimal(str(r["rate"]))
        return None

    # -------------------------------------------------------------- agent-scoped tools

    def agent_get(self, path: str, params: dict[str, Any] | None = None) -> Any:
        return self._request("GET", "/agent/tools" + path, token=self._delegated(), params=params)

    def agent_propose(self, body: dict[str, Any], idempotency_key: str) -> Any:
        return self._request("POST", "/agent/tools/proposals", token=self._delegated(), json=body,
                             headers={"Idempotency-Key": idempotency_key})

    def _delegated(self) -> str:
        if self._agent_token is None:
            self._agent_token = self._request("POST", "/auth/delegate", token=self._user_token)["accessToken"]
        return self._agent_token

    def _request(self, method: str, path: str, token: str, **kwargs) -> Any:
        headers = {"Authorization": f"Bearer {token}", **kwargs.pop("headers", {})}
        response = self._http.request(method, path, headers=headers, **kwargs)
        if response.status_code >= 400:
            try:
                body = response.json()
            except ValueError:
                body = {}
            raise FxApiError(response.status_code, body.get("code", "HTTP_ERROR"),
                             str(body.get("detail", response.reason_phrase))[:300])
        return response.json() if response.content else None
