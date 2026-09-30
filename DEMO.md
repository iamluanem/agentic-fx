# Demo

The stack starts already seeded:

| | |
|---|---|
| Customer | `demo@agentic.fx` / `demo-password` |
| Balances | BRL 30,000 · EUR 500 · USD 1,000 |
| Goal | "Europe Trip": acquire **€3,000** by **20 October** |
| Strategy | ACCUMULATE (progressive accumulation) |
| Mandate v1 | spend at most **R$18,000** · at most **R$4,000** per transaction · **approval above R$2,500** · human in the loop · active |

## Start

```bash
docker compose up --build
```

First build takes a few minutes (Maven, npm, pip). When `web` is healthy, open http://localhost:3000.

## Option A — scripted

```powershell
.\scripts\demo.ps1            # add -AutoApprove to skip the prompt
```
```bash
./scripts/demo.sh             # add --auto-approve
```

## Option B — in the UI (recommended in front of an audience)

Keep two browser tabs: **Dashboard** and **Settings**.

| # | Do | See |
|---|---|---|
| 1 | Settings → **Demo step 1: improve 1.15%** | The rate moves; the strategy engine detects it within a second |
| 2–5 | Dashboard | "Proposed R$ 2,000.00 → € 331…" · policy **ALLOW** (below R$2,500) · executes automatically · BRL 28,000 · progress ≈ 11% |
| — | Wait ~20 s | Agent status: "Last bought at …; waiting for at least 50 bps better". The market holding high does not trigger more buying |
| 6 | Settings → **Demo step 2: improve 1.85%** | The agent proposes **R$3,500** |
| 7–8 | Dashboard | Amber banner: 1 conversion waiting for your approval. Policy: **REQUIRE_APPROVAL** — *Above approval threshold* |
| 9 | Approvals → read the recommendation → **Approve conversion** | What happened, why, amount, fee, rate, remaining target, the mandate rule |
| 10–11 | Result on the card | Quote revalidated → policy re-run → **ALLOW** (*Approved by you*) → converted |
| 12 | Dashboard | BRL 24,500 · two segments on the progress bar · ≈ 30% |
| 13 | Goal → **Audit trail** | Two lanes per decision cycle: agent reasoning on the left; policy, approval and execution on the right; every step under mandate v1 |

Expected audit timeline:

```
Market condition detected   → Agent proposed conversion   | Policy evaluation: ALLOW → Conversion started → Conversion executed
Agent waiting
Market condition detected   → Agent proposed conversion   | Policy evaluation: REQUIRE_APPROVAL → Approval requested
                                                          | You approved → Quote revalidated → Policy evaluation: ALLOW
                                                          | → Conversion started → Conversion executed
```

## Talking points

- **Traditional automation** would have fired on a fixed rate. The agent sized each conversion by the strength of
  the move (R$2,000 for +1.15%, R$3,500 for +1.85%), refused to buy again at the same level, and stopped to ask
  when the amount crossed the customer's threshold.
- **Intelligence vs authority**: the proposal came from the agent; the decision came from `standard-v1` policy,
  shown with reason codes on every step.
- **Approval is not a bypass**: after approval the quote was refreshed and policy ran again.

## More to show

| Try | Result |
|---|---|
| Goals → New goal: *"I need €5,000 for my Europe trip before October 20."* | The agent asks only for the budget, then proposes a mandate with suggested values flagged |
| Same, in Portuguese: *"Preciso de 5.000 euros até 20 de outubro, posso gastar até R$ 32.000"* | Replies in Portuguese |
| Goal page → Ask the agent: *"Convert R$5,000 now"* | Proposal created by the agent, **DENIED** by policy (above R$4,000 per transaction) |
| Settings → **Worsen 1.2%** while an approval is pending, then approve | "Rate moved since approval" — you are asked again |
| Settings → **Fail next conversion**, then step 1 | Conversion FAILED, funds returned, audit shows it |
| Pause the agent while an approval is pending | The approval disappears; approving it is no longer possible |
| http://localhost:8080/actuator/prometheus | `fx_executions_total`, `policy_denials_total`, `approval_requests_total`, … |

## Reset

```bash
docker compose down -v && docker compose up --build
```

`-v` drops the database volume; the seed recreates the demo state on start.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Step 1 does nothing | The goal must be ACTIVE (Dashboard). Step buttons need fx-api in the `dev` profile (Compose sets it). |
| Step 2 converts without asking | You pressed step 2 before step 1 settled, or twice; reset and repeat in order. |
| "Market controls need fx-api running with the dev profile" | `SPRING_PROFILES_ACTIVE=dev` is missing. |
| Agent replies are rule-based | No `ANTHROPIC_API_KEY`; set it in `.env` and `docker compose up -d agent-service`. |
