from __future__ import annotations

import anthropic

from app.llm.provider import LlmError, LlmTurn, Message, ToolCall, ToolSpec


class AnthropicProvider:
    """Claude via the Messages API with tool use."""

    name = "anthropic"

    def __init__(self, api_key: str, model: str, timeout: float):
        self._client = anthropic.Anthropic(api_key=api_key, timeout=timeout, max_retries=1)
        self._model = model

    def complete(self, system: str, messages: list[Message], tools: list[ToolSpec],
                 force_tool: str | None = None, max_tokens: int = 1024) -> LlmTurn:
        kwargs = {
            "model": self._model,
            "max_tokens": max_tokens,
            "system": system,
            "messages": [self._to_wire(m) for m in messages],
        }
        if tools:
            kwargs["tools"] = [{"name": t.name, "description": t.description, "input_schema": t.input_schema}
                               for t in tools]
        if force_tool:
            kwargs["tool_choice"] = {"type": "tool", "name": force_tool}
        try:
            response = self._client.messages.create(**kwargs)
        except anthropic.APIError as e:
            raise LlmError(f"Anthropic API error: {type(e).__name__}") from e
        turn = LlmTurn(stop_reason=response.stop_reason or "")
        for block in response.content:
            if block.type == "text":
                turn.text += block.text
            elif block.type == "tool_use":
                turn.tool_calls.append(ToolCall(id=block.id, name=block.name, arguments=dict(block.input or {})))
        return turn

    @staticmethod
    def _to_wire(m: Message) -> dict:
        if m.role == "tool_results":
            return {"role": "user", "content": [
                {"type": "tool_result", "tool_use_id": call_id, "content": content, "is_error": is_error}
                for call_id, content, is_error in m.tool_results]}
        if m.role == "assistant":
            content = []
            if m.text:
                content.append({"type": "text", "text": m.text})
            content += [{"type": "tool_use", "id": c.id, "name": c.name, "input": c.arguments} for c in m.tool_calls]
            return {"role": "assistant", "content": content}
        return {"role": "user", "content": m.text}
