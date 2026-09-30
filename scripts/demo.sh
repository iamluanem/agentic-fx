#!/usr/bin/env bash
# Runs the Agentic FX demonstration against a running stack (docker compose up --build).
# Requires curl and jq.   Usage: ./scripts/demo.sh [--auto-approve]
set -euo pipefail

API="${API:-http://localhost:8080}"
EMAIL="${EMAIL:-demo@agentic.fx}"
PASSWORD="${PASSWORD:-demo-password}"
AUTO="${1:-}"

step() { printf '\n\033[36m== %s\033[0m\n' "$1"; }
say()  { printf '   %s\n' "$1"; }
uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen; }

TOKEN=$(curl -sf -X POST "$API/auth/token" -H 'content-type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" | jq -r .accessToken)

call() { # method path [body] [idempotency-key]
  local args=(-sf -X "$1" "$API$2" -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json')
  [[ -n "${3:-}" ]] && args+=(-d "$3")
  [[ -n "${4:-}" ]] && args+=(-H "Idempotency-Key: $4")
  curl "${args[@]}"
}

wait_for() { # description jq-filter-on-goal
  for _ in $(seq 1 60); do
    if call GET "/goals/$GOAL" | jq -e "$2" >/dev/null; then return 0; fi
    sleep 0.5
  done
  echo "Timed out waiting for: $1" >&2; exit 1
}

move_rate() { # factor
  local rate
  rate=$(call GET /fx/rates | jq --argjson f "$1" '.[] | select(.pair=="BRL_EUR") | (.reference * $f * 1e8 | round) / 1e8')
  call POST /dev/fx/rates "{\"pair\":\"BRL_EUR\",\"rate\":$rate}" >/dev/null
  say "BRL->EUR set to $rate"
}

step "Signed in as $EMAIL"
GOAL=$(call GET /goals | jq -r '[.[] | select(.status=="ACTIVE")][0].id')
[[ "$GOAL" == "null" ]] && { echo "No active goal; start the stack with the dev profile." >&2; exit 1; }
BEFORE=$(call GET "/goals/$GOAL" | jq '.conversions | length')
say "$(call GET "/goals/$GOAL" | jq -r '.goal | "\(.name): \(.acquired.display) of \(.targetAmount.display) by \(.deadline)"')"

step "Step 1 - the market improves 1.15%"
move_rate 1.0115
wait_for "first conversion" ".conversions | length > $BEFORE"
say "$(call GET "/goals/$GOAL" | jq -r '.conversions[0] | "Allowed and executed: \(.sourceAmount.display) -> \(.targetAmount.display)"')"

step "The market holds for 20 seconds"
sleep 20
say "$(call GET "/goals/$GOAL" | jq -r .agent.message)"

step "Step 2 - the market improves 1.85%"
move_rate 1.0185
wait_for "approval request" '.pendingApprovalId != null'
APPROVAL=$(call GET /approvals | jq '.[0]')
say "$(jq -r '.proposal.quote | "Recommendation: \(.sourceAmount.display) -> \(.targetAmount.display) at \(.rate)"' <<<"$APPROVAL")"
say "$(jq -r '.policy | "Policy: \(.decision) (\(.reasonCodes | join(", ")))"' <<<"$APPROVAL")"
ID=$(jq -r .id <<<"$APPROVAL")

if [[ "$AUTO" != "--auto-approve" ]]; then
  read -r -p "   Approve this conversion? [Y/n] " answer
  [[ "$answer" =~ ^[nN] ]] && { call POST "/approvals/$ID/reject" '{"reason":"Rejected in demo"}' "$(uuid)" >/dev/null; say "Rejected."; exit 0; }
fi

step "Approving - quote revalidated, policy re-evaluated, conversion executed"
call POST "/approvals/$ID/approve" "" "$(uuid)" \
  | jq -r '"Policy: \(.policy.decision)  Executed: \(.conversion.sourceAmount.display) -> \(.conversion.targetAmount.display)"' \
  | while read -r line; do say "$line"; done

step "Balances"
call GET /accounts/me/balances | jq -r '.[] | "   \(.currency)  \(.available.display)"'

step "Audit timeline"
call GET "/goals/$GOAL/audit" | jq -r '.[-14:][] | "   \(.timestamp[11:19])  \(.type)\(if .policyDecision then " [" + .policyDecision + "]" else "" end)"'
printf '\nOpen http://localhost:3000 to see the same story in the dashboard.\n'
