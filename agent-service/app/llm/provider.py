"""Provider-neutral LLM interface. Swapping Anthropic for another vendor means implementing {LlmProvider}."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Protocol


@dataclass(frozen=True)
class ToolSpec:
    name: str
    description: str
    input_schema: dict[str, Any]


@dataclass(frozen=True)
class ToolCall:
    id: str
    name: str
    arguments: dict[str, Any]


@dataclass
class LlmTurn:
    """One assistant turn: text and/or tool calls."""

    text: str = ""
    tool_calls: list[ToolCall] = field(default_factory=list)
    stop_reason: str = ""


@dataclass
class Message:
    """Neutral chat message. role: user | assistant | tool_results."""

    role: str
    text: str = ""
    tool_calls: list[ToolCall] = field(default_factory=list)
    tool_results: list[tuple[str, str, bool]] = field(default_factory=list)  # (tool_call_id, content, is_error)


class LlmError(RuntimeError):
    pass


class LlmProvider(Protocol):
    name: str

    def complete(self, system: str, messages: list[Message], tools: list[ToolSpec],
                 force_tool: str | None = None, max_tokens: int = 1024) -> LlmTurn:
        ...
