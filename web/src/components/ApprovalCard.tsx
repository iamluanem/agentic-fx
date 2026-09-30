"use client";

import { useState } from "react";

import { AgentVoice } from "@/components/AgentVoice";
import { DecisionStamp, Stamp, StatusStamp } from "@/components/Stamp";
import { ApiError, fxPost, newKey } from "@/lib/api";
import { describeRate, formatDateTime } from "@/lib/format";
import type { ApprovalView, OutcomeView } from "@/lib/types";

/** The human-in-the-loop moment: what the agent recommends, why, and what the mandate says. */
export function ApprovalCard({ approval, onDecided }: { approval: ApprovalView; onDecided: () => void }) {
  const [busy, setBusy] = useState<"approve" | "reject" | null>(null);
  const [result, setResult] = useState<OutcomeView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [keys] = useState({ approve: newKey(), reject: newKey() });
  const proposal = approval.proposal;
  if (!proposal) return null;
  const q = proposal.quote;
  const e = proposal.explanation;
  const rate = describeRate(q.pair, q.rate);

  async function decide(kind: "approve" | "reject") {
    setBusy(kind);
    setError(null);
    try {
      const outcome = await fxPost<OutcomeView>(`/approvals/${approval.id}/${kind}`, kind === "reject" ? {} : undefined,
        keys[kind]);
      setResult(outcome);
      onDecided();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not reach the server.");
    } finally {
      setBusy(null);
    }
  }

  return (
    <article className="panel overflow-hidden">
      <header className="flex flex-wrap items-center justify-between gap-2 border-b border-rule px-5 py-3">
        <div>
          <h3 className="text-sm font-semibold">{approval.goalName ?? "Goal"}</h3>
          <p className="text-xs text-ink-faint">
            Requested {formatDateTime(approval.requestedAt)}, expires {formatDateTime(approval.expiresAt)}
          </p>
        </div>
        {approval.policy && <DecisionStamp decision={approval.policy.decision} codes={approval.policy.reasonCodes} />}
      </header>

      <div className="grid gap-5 px-5 py-4 md:grid-cols-[1fr_1.2fr]">
        <div>
          <p className="label">Convert</p>
          <p className="mt-1 text-2xl font-semibold tracking-tight">
            {q.sourceAmount.display} <span className="text-ink-faint">→</span> {q.targetAmount.display}
          </p>
          <dl className="mt-3 space-y-1 text-sm">
            <div className="flex justify-between">
              <dt className="text-ink-soft">Rate</dt>
              <dd>{rate.direct}</dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-ink-soft" />
              <dd className="text-xs text-ink-faint">{rate.inverse}</dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-ink-soft">Estimated fee</dt>
              <dd>{q.fee.display}</dd>
            </div>
            {e && (
              <div className="flex justify-between">
                <dt className="text-ink-soft">Remaining after</dt>
                <dd>{e.remainingTargetAfter.display}</dd>
              </div>
            )}
          </dl>
          <p className="mt-3 text-xs text-ink-faint">The quote is re-checked when you approve; if the rate worsens by more than 0.5% you will be asked again.</p>
        </div>

        <div className="space-y-3">
          {e && (
            <AgentVoice label={proposal.origin === "LLM_AGENT" ? "Agent recommendation" : "Strategy engine recommendation"}>
              <p>{e.whatHappened}</p>
              <p className="mt-1">{e.why}</p>
            </AgentVoice>
          )}
          {e && <p className="text-xs text-ink-soft">{e.mandateRule}</p>}
        </div>
      </div>

      <footer className="flex flex-wrap items-center gap-3 border-t border-rule bg-canvas/50 px-5 py-3">
        {result ? (
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <StatusStamp status={result.proposal?.status ?? "PROPOSED"} />
            {result.conversion && (
              <Stamp tone="allow">
                Converted {result.conversion.sourceAmount.display} → {result.conversion.targetAmount.display}
              </Stamp>
            )}
            {result.policy && <span className="text-xs text-ink-soft">{result.policy.explanation}</span>}
          </div>
        ) : (
          <>
            <button className="btn-allow" disabled={busy !== null} onClick={() => decide("approve")}>
              {busy === "approve" ? "Approving…" : "Approve conversion"}
            </button>
            <button className="btn-deny" disabled={busy !== null} onClick={() => decide("reject")}>
              {busy === "reject" ? "Rejecting…" : "Reject"}
            </button>
          </>
        )}
        {error && <p className="text-sm text-deny">{error}</p>}
      </footer>
    </article>
  );
}
