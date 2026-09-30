"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";

import { AgentVoice } from "@/components/AgentVoice";
import { MandateCard } from "@/components/MandateCard";
import { ProgressLedger } from "@/components/ProgressLedger";
import { PageHeader } from "@/components/Shell";
import { DecisionStamp, StatusStamp } from "@/components/Stamp";
import { Timeline } from "@/components/Timeline";
import { agentPost, ApiError, fxPost, newKey, usePoll } from "@/lib/api";
import { daysUntil, describeRate, formatDate, formatDateTime, STRATEGY_TEXT } from "@/lib/format";
import type { AdviseResponse, AuditView, GoalDetailView } from "@/lib/types";

export default function GoalPage() {
  const { id } = useParams<{ id: string }>();
  const { data, error, reload } = usePoll<GoalDetailView>(`/goals/${id}`, 3000);
  const { data: audit } = usePoll<AuditView[]>(`/goals/${id}/audit`, 3000);
  const [busy, setBusy] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  if (error && !data) return <p className="text-sm text-deny">{error.message}</p>;
  if (!data) return <p className="text-sm text-ink-soft">Loading goal…</p>;
  const { goal, mandate, agent, openProposal } = data;

  async function lifecycle(action: "activate" | "pause" | "resume" | "cancel") {
    if (action === "cancel" && !confirm("Cancel this goal? The mandate is revoked and cannot be reactivated.")) return;
    setBusy(action);
    setActionError(null);
    try {
      await fxPost(`/goals/${goal.id}/${action}`, undefined, action === "activate" ? newKey() : undefined);
      await reload();
    } catch (e) {
      setActionError(e instanceof ApiError ? e.message : "Could not reach the server.");
    } finally {
      setBusy(null);
    }
  }

  return (
    <>
      <PageHeader
        title={goal.name}
        subtitle={`${STRATEGY_TEXT[goal.strategy.type]}. Deadline ${formatDate(goal.deadline)} (${daysUntil(goal.deadline)} days left).`}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <StatusStamp status={goal.status} />
            {goal.status === "DRAFT" && (
              <button className="btn-allow" disabled={!!busy} onClick={() => lifecycle("activate")}>Activate agent</button>
            )}
            {goal.status === "ACTIVE" && (
              <button className="btn-quiet" disabled={!!busy} onClick={() => lifecycle("pause")}>Pause agent</button>
            )}
            {goal.status === "PAUSED" && (
              <button className="btn-quiet" disabled={!!busy} onClick={() => lifecycle("resume")}>Resume agent</button>
            )}
            {["DRAFT", "ACTIVE", "PAUSED"].includes(goal.status) && (
              <button className="btn-deny" disabled={!!busy} onClick={() => lifecycle("cancel")}>Cancel goal</button>
            )}
          </div>
        }
      />
      {actionError && <p className="mb-4 text-sm text-deny" role="alert">{actionError}</p>}

      <div className="grid gap-6 lg:grid-cols-[1.6fr_1fr]">
        <div className="space-y-6">
          <section className="panel p-5">
            <ProgressLedger goal={goal} conversions={data.conversions} />
          </section>

          <section className="space-y-3" aria-label="Agent">
            <AgentVoice label={`Agent is ${agent.state.toLowerCase().replaceAll("_", " ")}`}>{agent.message}</AgentVoice>
            {openProposal && (
              <div className="panel flex flex-wrap items-center justify-between gap-3 p-4 text-sm">
                <span>
                  Open proposal: {openProposal.quote.sourceAmount.display} → {openProposal.quote.targetAmount.display}
                </span>
                <span className="flex items-center gap-2">
                  <StatusStamp status={openProposal.status} />
                  {data.pendingApprovalId && <Link href="/approvals" className="btn-allow">Review</Link>}
                </span>
              </div>
            )}
            {goal.status === "ACTIVE" && <AskAgent goalId={goal.id} onDone={reload} />}
          </section>

          <section className="panel p-5">
            <h2 className="mb-4 text-sm font-semibold">Audit trail</h2>
            <Timeline events={audit ?? []} newestFirst />
          </section>
        </div>

        <aside className="space-y-6">
          {mandate && <MandateCard mandate={mandate} />}
          {data.mandateVersions.length > 1 && (
            <div className="panel p-4 text-sm">
              <h3 className="mb-2 font-semibold">Mandate history</h3>
              <ul className="space-y-1 text-ink-soft">
                {data.mandateVersions.map((m) => (
                  <li key={m.version} className="flex justify-between">
                    <span>v{m.version} ({m.executionMode === "AUTONOMOUS" ? "autonomous" : "human approval"})</span>
                    <StatusStamp status={m.status} />
                  </li>
                ))}
              </ul>
            </div>
          )}
          <section className="panel p-4">
            <h3 className="mb-3 text-sm font-semibold">Conversions</h3>
            {data.conversions.length === 0 ? (
              <p className="text-sm text-ink-soft">No conversions yet.</p>
            ) : (
              <ul className="divide-y divide-rule text-sm">
                {data.conversions.map((c) => (
                  <li key={c.id} className="py-2">
                    <div className="flex justify-between font-medium">
                      <span>{c.sourceAmount.display} → {c.targetAmount.display}</span>
                    </div>
                    <div className="text-xs text-ink-faint">
                      {describeRate(c.pair, c.rate).direct}, fee {c.fee.display}, mandate v{c.mandateVersion},{" "}
                      {formatDateTime(c.executedAt)}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </aside>
      </div>
    </>
  );
}

function AskAgent({ goalId, onDone }: { goalId: string; onDone: () => void }) {
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [answer, setAnswer] = useState<AdviseResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function ask(e: React.FormEvent) {
    e.preventDefault();
    if (!message.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const response = await agentPost<AdviseResponse>("/agent/advise", {
        goalId, message: message.trim(), requestId: newKey(),
      });
      setAnswer(response);
      setMessage("");
      onDone();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "The agent is not reachable right now.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="panel p-4">
      <form onSubmit={ask} className="flex gap-2">
        <label htmlFor="ask-agent" className="sr-only">Ask the agent</label>
        <input id="ask-agent" className="field" value={message} maxLength={2000} disabled={busy}
               placeholder="Ask the agent, e.g. “Is now a good moment?” or “Convert R$1,000 now”"
               onChange={(e) => setMessage(e.target.value)} />
        <button className="btn-primary" disabled={busy || !message.trim()}>{busy ? "Thinking…" : "Ask"}</button>
      </form>
      {error && <p className="mt-2 text-sm text-deny" role="alert">{error}</p>}
      {answer && (
        <div className="mt-3 space-y-2">
          <AgentVoice compact>{answer.reply}</AgentVoice>
          {answer.proposal?.policy && (
            <DecisionStamp decision={answer.proposal.policy.decision} codes={answer.proposal.policy.reasonCodes} />
          )}
          {answer.actions.length > 0 && (
            <details className="text-xs text-ink-soft">
              <summary className="cursor-pointer">Tools the agent used ({answer.actions.length})</summary>
              <ul className="mt-1 space-y-0.5">
                {answer.actions.map((a, i) => (
                  <li key={i}>{a.ok ? "✓" : "✗"} {a.tool}: {a.summary}</li>
                ))}
              </ul>
            </details>
          )}
        </div>
      )}
    </div>
  );
}
