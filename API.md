# API

Interactive docs while the stack runs: **fx-api** http://localhost:8080/swagger-ui.html ·
**agent-service** http://localhost:8000/docs.

## Conventions

- `Authorization: Bearer <JWT>` on every call except `POST /auth/token`.
- Financial POSTs (`/goals`, `/goals/{id}/activate`, `/goals/{id}/mandates/{v}/activate`, `/approvals/{id}/*`,
  `/agent/tools/proposals`) require `Idempotency-Key`. Retrying with the same key and body returns the original
  response with `Idempotent-Replay: true`; the same key with a different body returns 409 `IDEMPOTENCY_KEY_REUSED`.
- `X-Correlation-Id` is accepted and echoed; it is stored on audit events.
- Money is `{ "amount": 2000.00, "currency": "BRL", "display": "R$ 2,000.00" }`. Rates are *target per 1 source*:
  `BRL_EUR = 0.165` means 1 BRL = 0.165 EUR.
- Errors are RFC 7807 problem documents with a stable `code`:

```json
{ "status": 409, "detail": "Approval is already APPROVED", "code": "APPROVAL_ALREADY_DECIDED", "correlationId": "…" }
```

## fx-api

### Auth

| Method | Path | Role | |
|---|---|---|---|
| POST | `/auth/token` | — | `{email, password}` → `{accessToken, expiresAt, roles}` |
| POST | `/auth/delegate` | CUSTOMER | 10-minute `AGENT` token for the agent-service |
| GET | `/me` | CUSTOMER | profile |

### Goals and mandates

| Method | Path | |
|---|---|---|
| POST | `/goals` | create goal + mandate v1, both DRAFT |
| GET | `/goals` | list |
| GET | `/goals/{id}` | goal, current mandate, versions, agent status, open proposal, pending approval, decisions, conversions |
| POST | `/goals/{id}/activate` | explicit activation of goal + draft mandate |
| POST | `/goals/{id}/pause` · `/resume` · `/cancel` | lifecycle (pause/cancel discard open proposals) |
| POST | `/goals/{id}/mandates` | draft mandate version N+1 (current stays in force) |
| POST | `/goals/{id}/mandates/{version}/activate` | supersede current version |
| GET | `/goals/{id}/audit` | immutable timeline, oldest first |
| GET | `/goals/{id}/conversions` · `/decisions` | |

```http
POST /goals
Idempotency-Key: 7c9e…
{
  "name": "Europe Trip",
  "sourceCurrency": "BRL", "targetCurrency": "EUR",
  "targetAmount": 5000, "deadline": "2026-10-20", "maxBudget": 32000,
  "strategyType": "ACCUMULATE",
  "mandate": {
    "maxTotalSpend": 32000, "maxTransactionAmount": 5000, "approvalThreshold": 3000,
    "allowPartialExecution": true, "executionMode": "HUMAN_IN_THE_LOOP"
  }
}
→ 201 { "goal": { "status": "DRAFT", … }, "mandate": { "version": 1, "status": "DRAFT", … } }
```

### Approvals (CUSTOMER only — the agent token cannot reach these)

| Method | Path | |
|---|---|---|
| GET | `/approvals?status=pending\|all` | recommendation, quote, explanation, policy decision |
| POST | `/approvals/{id}/approve` | revalidate quote → re-run policy → execute (or ask again if the rate moved) |
| POST | `/approvals/{id}/reject` | `{reason?}` |

```json
{
  "proposal": { "status": "COMPLETED", "mandateVersion": 1, "quote": { "rate": 0.1685, … } },
  "policy": { "decision": "ALLOW", "reasonCodes": ["HUMAN_APPROVAL_GRANTED", "WITHIN_MANDATE_LIMITS"],
              "policyVersion": "standard-v1" },
  "conversion": { "sourceAmount": { "display": "R$ 3,500.00" }, "targetAmount": { "display": "€ 586.21" } }
}
```

### Market, account, activity

| Method | Path | |
|---|---|---|
| GET | `/fx/rates` | `[{pair, rate, reference, changeBps, observedAt}]` (reference = 60-minute average) |
| GET | `/fx/quotes?source=BRL&target=EUR&amount=1000` | indicative quote |
| POST | `/dev/fx/rates` | `{pair, rate}` — **dev profile only**, publishes like a market tick |
| POST | `/dev/provider/fail-next` | **dev profile only**, next conversion fails (refund path) |
| GET | `/accounts/me/balances` | |
| GET | `/activity?limit=50` | the customer's audit events, newest first |

### Agent tools (AGENT or CUSTOMER)

| Method | Path | Tool |
|---|---|---|
| GET | `/agent/tools/balances` | `get_balances` |
| GET | `/agent/tools/quote?source&target&amount` | `get_fx_quote` |
| GET | `/agent/tools/goals`, `/agent/tools/goals/{id}` | `get_goal` |
| GET | `/agent/tools/goals/{id}/progress` | `get_goal_progress` |
| POST | `/agent/tools/proposals` | `create_execution_proposal` / `request_user_approval` |
| GET | `/agent/tools/proposals/{id}` | `get_transaction_status` |

```http
POST /agent/tools/proposals
Idempotency-Key: req-4f2…-p1
{ "goalId": "…", "sourceAmount": 1000, "rationale": "…", "marketContext": "…", "requireApproval": false }
→ 201 { "proposal": {…}, "policy": { "decision": "ALLOW" | "REQUIRE_APPROVAL" | "DENY", "reasonCodes": [...] },
        "approvalId": null, "conversion": {…} }
```

### Operations

`/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/prometheus`, `/v3/api-docs`.

## agent-service

| Method | Path | |
|---|---|---|
| POST | `/agent/intents` | `{message, previousIntent?, awaitingFields?}` → `IntentResponse` |
| POST | `/agent/advise` | `{goalId, message, requestId}` → `{reply, actions[], proposal?, agent}` |
| GET | `/health` | `{status, llm: "anthropic" \| "deterministic"}` |

```http
POST /agent/intents
{ "message": "I need €5,000 by October 20. I can spend up to R$32,000. Buy gradually but ask me before spending more than R$3,000." }
→ 200
{
  "status": "READY",
  "intent": {
    "sourceCurrency": "BRL", "targetCurrency": "EUR", "targetAmount": "5000.00", "deadline": "2026-10-20",
    "maxBudget": "32000.00", "executionMode": "HUMAN_IN_THE_LOOP", "allowPartialExecution": true,
    "maxTransactionAmount": "8000.00", "approvalThreshold": "3000.00", "strategyType": "ACCUMULATE"
  },
  "missing": [], "questions": [],
  "defaultsApplied": [{ "field": "maxTransactionAmount", "value": "8000", "reason": "A quarter of the budget…" }],
  "warnings": [], "insights": ["At the current simulated rate, €5,000 would cost about R$30,486 including fees…"],
  "reply": "Here is the proposed mandate…", "parser": "anthropic", "language": "en"
}
```

With a missing budget the response is `"status": "NEEDS_INPUT", "missing": ["maxBudget"],
"questions": ["What is the most you are willing to spend in BRL?"]`; send the answer with `previousIntent` and
`awaitingFields` to continue.
