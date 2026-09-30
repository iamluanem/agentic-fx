"""Verifies the customer's JWT (issued by fx-api, HS256). The agent-service trusts nothing else: the verified
token is what it later exchanges for a restricted AGENT token."""

from __future__ import annotations

from dataclasses import dataclass

import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.config import Settings, get_settings

_bearer = HTTPBearer(auto_error=False)


@dataclass(frozen=True)
class Caller:
    user_id: str
    token: str


def current_customer(credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
                     settings: Settings = Depends(get_settings)) -> Caller:
    if credentials is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Missing bearer token")
    if len(settings.jwt_secret.encode()) < 32:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Service is not configured (JWT_SECRET)")
    try:
        claims = jwt.decode(credentials.credentials, settings.jwt_secret, algorithms=["HS256"],
                            issuer=settings.jwt_issuer, options={"require": ["exp", "sub", "iss"]})
    except jwt.PyJWTError:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid or expired token") from None
    if "CUSTOMER" not in (claims.get("roles") or []):
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Customer token required")
    return Caller(user_id=claims["sub"], token=credentials.credentials)
