from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """All configuration comes from environment variables; secrets are never hard-coded."""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    fx_api_url: str = "http://localhost:8080"
    jwt_secret: str = Field(default="", repr=False)
    jwt_issuer: str = "agentic-fx"

    # LLM: "anthropic" when a key is present, otherwise the deterministic fallback is used.
    llm_provider: str = "auto"
    anthropic_api_key: str = Field(default="", repr=False)
    anthropic_model: str = "claude-sonnet-5-5"
    llm_timeout_seconds: float = 20.0
    agent_max_turns: int = 6

    business_timezone: str = "America/Sao_Paulo"
    max_message_chars: int = 2000

    def llm_enabled(self) -> bool:
        if self.llm_provider == "none":
            return False
        return bool(self.anthropic_api_key)


@lru_cache
def get_settings() -> Settings:
    return Settings()
