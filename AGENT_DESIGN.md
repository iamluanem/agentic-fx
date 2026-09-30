# Agent design

## Roles

| Component | Kind | Authority |
|---|---|---|
| Intent parser (agent-service) | LLM + deterministic fallback | none — produces a **draft** |
| Strategy Engine (fx-core) | deterministic | none — produces **proposals** |
| Advisory agent (agent-service) | LLM with tools + deterministic fallback | none — produces **proposals** |
| Policy Engine (fx-core) | deterministic, versioned | decides ALLOW / REQUIRE_APPROVAL / DENY |
| Customer | human | activates mandates, approves, pauses, cancels |
| Execution Engine (fx-core) | deterministic | executes only what policy (and the customer) allowed |

The Strategy Engine is the always-on agent loop (Monitor → Plan → Propose); the LLM agent is an on-demand
conversational layer over the same tools. Both feed the same `ExecutionService.propose` path.

## 1. Natural language → mandate

```
customer text
  └─► LLM parser (Claude, forced tool call `submit_intent`, JSON schema)       ─┐ on any failure:
        └─► Pydantic StructuredIntent (extra=forbid, enums, ranges)             ├─► deterministic parser
              └─► grounding: every new amount must appear in the text          ─┘   (EN + PT-BR rules)
                    └─► merge with previous turns
                          └─► validation: safe defaults (reported), consistency, missing fields → questions
                                └─► market insight (budget vs cost at the live rate)
                                      └─► READY → draft mandate shown to the customer → explicit activation
```

- **Asks only what is necessary.** Required: target currency, amount, deadline, budget. Everything else gets a
  safe default that is flagged in the UI (`per conversion = budget / 4`, `execution = human approval`,
  `partial = allowed`, `strategy = progressive accumulation`).
- **Multi-turn.** The previous intent and the fields being awaited are sent back, so "R$32.000" answers the
  budget question.
- **Portuguese and English**, including Brazilian number formats (`32.000,50`), "5 mil", "20 de outubro",
  inverse rate quotes ("dólar a R$5,40" → 0.18518 USD per BRL).

## 2. Strategy Engine

Pure function of: goal, mandate, latest observation (rate + 60-minute reference), available balance, committed
spend, open proposal, last fill, last rejection, today.

| Strategy | When it proposes | How much |
|---|---|---|
| `ACCUMULATE` | improvement over reference ≥ goal's minimum (default 50 bps) | `maxTransaction × min(1, improvement / (4 × minimum))`, rounded down to R$500 |
| `TARGET_RATE` | rate ≥ target rate | everything still needed, capped by limits |
| `DEADLINE_AWARE` | required improvement shrinks linearly to 0 at the deadline; buys at market on the last day | as ACCUMULATE, with a minimum pace when more than one tranche behind schedule |

Always: never while another proposal is open; never above balance, mandate room or goal budget; after a fill,
wait for a materially better rate (+min bps) or a 6-hour cool-down; after a rejection, cool down; the final
tranche buys exactly what is needed. Waiting decisions are recorded only when the agent's state changes.

## 3. Policy Engine

`PolicyDecision evaluate(AgentMandate, ExecutionProposal, AccountState)` — all rules always run; any DENY finding
denies; otherwise any approval finding requires approval; otherwise ALLOW with the permitting rules as reason
codes. Rules (policy `standard-v1`):

| Rule | Effect |
|---|---|
| mandate ACTIVE, version matches proposal, not expired | DENY |
| goal ACTIVE, deadline not passed (business time zone) | DENY |
| proposal in an evaluable state | DENY |
| source/target currency allowed | DENY |
| quote not expired | DENY |
| balance sufficient | DENY |
| ≤ max per transaction | DENY |
| spent + in-flight + this ≤ max total spend, ≤ goal budget | DENY |
| goal not reached; ≤ remaining target (+1%); partial allowed | DENY |
| above approval threshold / HITL without threshold | REQUIRE_APPROVAL |
| approved, but rate worse by > 0.5% since approval | REQUIRE_APPROVAL |

**Execution modes.** The approval threshold, when set, applies in both modes. `HUMAN_IN_THE_LOOP` without a
threshold asks for every conversion; `AUTONOMOUS` without a threshold executes anything inside the limits.

## 4. Tools exposed to the LLM

| Tool | fx-api | Notes |
|---|---|---|
| `get_balances()` | `GET /agent/tools/balances` | |
| `get_fx_quote(source, target, amount)` | `GET /agent/tools/quote` | indicative, not executable |
| `get_goal(goal_id)` | `GET /agent/tools/goals/{id}` | includes the active mandate — the limits to respect |
| `get_goal_progress(goal_id)` | `GET /agent/tools/goals/{id}/progress` | |
| `create_execution_proposal(goal_id, source_amount, rationale, market_context)` | `POST /agent/tools/proposals` | policy decides; one per turn |
| `request_user_approval(...)` | same, `requireApproval=true` | makes execution more conservative |
| `get_transaction_status(proposal_id)` | `GET /agent/tools/proposals/{id}` | |

Not exposed, by design: `execute_payment`, `execute_conversion`, approve, activate. The registry raises if a tool
name contains `execute`, `approve`, `convert`, `transfer`, `payment` or `withdraw`.

Tool calls use a delegated `AGENT` token (10 min). Results are passed to the model as JSON data, clipped to 4 KB;
code paths use the full result. The loop stops after 6 turns. If the LLM fails after a proposal was created, the
agent reports the outcome and never creates a second one.

## 5. Explainability

Every proposal carries a structured `Explanation`: what happened, why, amount, expected amount, fee, rate,
remaining target after, and the mandate rule that permits it. Numbers come from the quote and the goal — never
from model text — so the narrative cannot contradict what policy evaluated. LLM text is sanitised (control
characters stripped, 500 chars) and stored as data. Every policy decision stores all findings, not only the
decisive ones, with the policy version.

Example (strategy engine, demo step 2):

> BRL now buys 1.87% more EUR than the recent reference rate (0.16541 → 0.16850). Mandate v1 allows partial
> conversions up to R$ 4,000.00 each and R$ 18,000.00 in total, with your approval above R$ 2,500.00.
> I recommend converting R$ 3,500.00 into about € 586.21 at 1 BRL = 0.1685 EUR (fee R$ 21.00). Progressive
> accumulation: stronger improvements earn larger tranches. This tranche is 93% of the per-transaction limit.
> After this conversion, € 2,082.49 remains to reach the goal.

## 6. Provider abstraction

`LlmProvider.complete(system, messages, tools, force_tool)` returns text and tool calls in a neutral shape.
`AnthropicProvider` implements it with the Messages API; adding another vendor is one class. The same pattern
exists on the money side: `FxProvider.getQuote / execute` with `MockFxProvider`, `WiseFxProvider`,
`RevolutFxProvider`.
