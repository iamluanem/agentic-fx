"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";

import { AgentVoice } from "@/components/AgentVoice";
import { PageHeader } from "@/components/Shell";
import { Stamp } from "@/components/Stamp";
import { agentPost, ApiError, fxPost, newKey } from "@/lib/api";
import { formatDate, MODE_TEXT, STRATEGY_TEXT } from "@/lib/format";
import type { GoalView, IntentResponse, MandateView, StructuredIntent } from "@/lib/types";

const FIELD_LABEL: Record<string, string> = {
  maxTransactionAmount: "Per conversion",
  executionMode: "Execution",
  allowPartialExecution: "Split into parts",
  strategyType: "Strategy",
  sourceCurrency: "Pay from",
};

type Turn = { role: "user"; text: string } | { role: "agent"; response: IntentResponse };

const SYMBOL: Record<string, string> = { BRL: "R$", EUR: "€", USD: "$", GBP: "£" };
const money = (amount: string | null, currency: string | null) =>
  amount == null ? "—" : `${SYMBOL[currency ?? ""] ?? ""}${Number(amount).toLocaleString("en-US", { maximumFractionDigits: 2 })}`;

export default function NewGoalPage() {
  const router = useRouter();
  const [turns, setTurns] = useState<Turn[]>([]);
  const [message, setMessage] = useState("");
  const [thinking, setThinking] = useState(false);
  const [intent, setIntent] = useState<StructuredIntent | null>(null);
  const [latest, setLatest] = useState<IntentResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => endRef.current?.scrollIntoView({ behavior: "smooth", block: "nearest" }), [turns]);

  async function send(e: React.FormEvent) {
    e.preventDefault();
    const text = message.trim();
    if (!text) return;
    setTurns((t) => [...t, { role: "user", text }]);
    setMessage("");
    setThinking(true);
    setError(null);
    try {
      const response = await agentPost<IntentResponse>("/agent/intents", {
        message: text,
        previousIntent: intent,
        awaitingFields: latest?.missing ?? [],
      });
      setTurns((t) => [...t, { role: "agent", response }]);
      setIntent(response.intent);
      setLatest(response);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "The agent is not reachable right now.");
    } finally {
      setThinking(false);
    }
  }

  return (
    <>
      <PageHeader title="What do you want your money to do?"
                  subtitle="Describe the goal in your own words. The agent turns it into a mandate you can review; nothing happens until you activate it." />
      <div className="grid gap-6 lg:grid-cols-[1fr_1fr]">
        <section className="panel flex min-h-[420px] flex-col p-5" aria-label="Conversation with the agent">
          <div className="flex-1 space-y-4" aria-live="polite">
            {turns.length === 0 && (
              <div className="space-y-2 text-sm text-ink-soft">
                <p>Try something like:</p>
                {["I need €5,000 for my Europe trip before October 20.",
                  "I need €5,000 by October 20. I can spend up to R$32,000. Buy gradually but ask me before spending more than R$3,000.",
                  "Preciso de US$ 2.000 até dezembro, posso gastar até R$ 11.500."].map((example) => (
                  <button key={example} type="button" onClick={() => setMessage(example)}
                          className="block w-full rounded-lg border border-rule px-3 py-2 text-left hover:bg-canvas">
                    {example}
                  </button>
                ))}
              </div>
            )}
            {turns.map((turn, i) =>
              turn.role === "user" ? (
                <p key={i} className="ml-auto max-w-[85%] rounded-panel bg-ink px-4 py-2.5 text-sm text-white">{turn.text}</p>
              ) : (
                <AgentReply key={i} response={turn.response} />
              ),
            )}
            {thinking && <p className="text-sm text-agent">The agent is reading your goal…</p>}
            {error && <p className="text-sm text-deny" role="alert">{error}</p>}
            <div ref={endRef} />
          </div>
          <form onSubmit={send} className="mt-4 flex gap-2">
            <label className="sr-only" htmlFor="goal-message">Your goal</label>
            <input id="goal-message" className="field" value={message} maxLength={2000}
                   placeholder={latest?.status === "NEEDS_INPUT" ? "Your answer" : "I need €5,000 for my Europe trip before October 20."}
                   onChange={(e) => setMessage(e.target.value)} disabled={thinking} />
            <button className="btn-primary" disabled={thinking || !message.trim()}>Send</button>
          </form>
        </section>

        {intent && latest?.status === "READY" ? (
          <MandateProposal intent={intent} response={latest} onChange={setIntent}
                           onActivated={(id) => router.push(`/goals/${id}`)} />
        ) : (
          <section className="panel grid place-items-center p-8 text-center text-sm text-ink-soft">
            The proposed mandate appears here once the agent has the target, the deadline and your budget.
          </section>
        )}
      </div>
    </>
  );
}

function Row({ label, suggested, children }: { label: string; suggested?: boolean; children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[150px_1fr] items-center gap-3 py-2">
      <span className="text-sm text-ink-soft">
        {label}
        {suggested && <span className="ml-1 text-xs text-agent">(suggested)</span>}
      </span>
      <div className="text-sm font-medium">{children}</div>
    </div>
  );
}

function AgentReply({ response }: { response: IntentResponse }) {
  return (
    <AgentVoice label={response.parser === "anthropic" ? "Agent" : "Agent (rule-based parser)"}>
      <p>{response.reply}</p>
      {response.insights.map((s) => <p key={s} className="mt-2 text-ink-soft">{s}</p>)}
      {response.warnings.map((w) => <p key={w} className="mt-2 text-approval">{w}</p>)}
    </AgentVoice>
  );
}

function MandateProposal({ intent, response, onChange, onActivated }: {
  intent: StructuredIntent;
  response: IntentResponse;
  onChange: (i: StructuredIntent) => void;
  onActivated: (goalId: string) => void;
}) {
  const [busy, setBusy] = useState<"draft" | "activate" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState<{ goal: GoalView; mandate: MandateView } | null>(null);
  const [keys] = useState({ create: newKey(), activate: newKey() });
  const suggested = new Set(response.defaultsApplied.map((d) => d.field));
  const src = intent.sourceCurrency, tgt = intent.targetCurrency;

  const set = (field: keyof StructuredIntent) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
    const value = e.target.type === "checkbox" ? (e.target as HTMLInputElement).checked : e.target.value || null;
    onChange({ ...intent, [field]: value });
  };

  async function submit(activate: boolean) {
    setBusy(activate ? "activate" : "draft");
    setError(null);
    try {
      const draft = created ?? (await fxPost<{ goal: GoalView; mandate: MandateView }>("/goals", {
        name: intent.name,
        sourceCurrency: src,
        targetCurrency: tgt,
        targetAmount: intent.targetAmount,
        deadline: intent.deadline,
        maxBudget: intent.maxBudget,
        strategyType: intent.strategyType,
        targetRate: intent.targetRate,
        mandate: {
          maxTotalSpend: intent.maxBudget,
          maxTransactionAmount: intent.maxTransactionAmount,
          approvalThreshold: intent.approvalThreshold,
          allowPartialExecution: intent.allowPartialExecution,
          executionMode: intent.executionMode,
        },
      }, keys.create));
      setCreated(draft);
      if (activate) {
        await fxPost(`/goals/${draft.goal.id}/activate`, undefined, keys.activate);
      }
      onActivated(draft.goal.id);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not reach the server.");
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className="panel p-5" aria-label="Proposed agent mandate">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">Proposed agent mandate</h2>
        <Stamp tone="neutral">Draft</Stamp>
      </div>
      <fieldset disabled={created !== null} className="mt-3 divide-y divide-rule">
        <Row label="Goal">
          <input className="field" value={intent.name ?? ""} onChange={set("name")} maxLength={120} />
        </Row>
        <Row label="Acquire">{money(intent.targetAmount, tgt)} {tgt}</Row>
        <Row label="Deadline">{formatDate(intent.deadline)}</Row>
        <Row label="Budget">{money(intent.maxBudget, src)} {src}</Row>
        <Row label="Strategy" suggested={suggested.has("strategyType")}>
          <select className="field" value={intent.strategyType ?? "ACCUMULATE"} onChange={set("strategyType")}>
            {Object.entries(STRATEGY_TEXT).map(([k, v]) => (
              <option key={k} value={k} disabled={k === "TARGET_RATE" && !intent.targetRate}>{v}</option>
            ))}
          </select>
        </Row>
        <Row label="Per conversion" suggested={suggested.has("maxTransactionAmount")}>
          <input className="field" inputMode="decimal" value={intent.maxTransactionAmount ?? ""}
                 onChange={set("maxTransactionAmount")} aria-label={`Maximum per conversion in ${src}`} />
        </Row>
        <Row label="Approval above">
          <input className="field" inputMode="decimal" value={intent.approvalThreshold ?? ""} placeholder="Every conversion"
                 onChange={set("approvalThreshold")} aria-label={`Approval threshold in ${src}`} />
        </Row>
        <Row label="Execution" suggested={suggested.has("executionMode")}>
          <select className="field" value={intent.executionMode ?? "HUMAN_IN_THE_LOOP"} onChange={set("executionMode")}>
            {Object.entries(MODE_TEXT).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </Row>
        <Row label="Split into parts" suggested={suggested.has("allowPartialExecution")}>
          <label className="flex items-center gap-2 font-normal">
            <input type="checkbox" checked={intent.allowPartialExecution ?? true} onChange={set("allowPartialExecution")} />
            Allow several conversions
          </label>
        </Row>
      </fieldset>

      {response.defaultsApplied.length > 0 && (
        <ul className="mt-3 space-y-1 text-xs text-ink-soft">
          {response.defaultsApplied.map((d) => <li key={d.field}>{FIELD_LABEL[d.field] ?? d.field}: {d.reason}</li>)}
        </ul>
      )}

      <p className="mt-4 rounded-lg bg-canvas px-3 py-2 text-xs leading-relaxed text-ink-soft">
        Activating authorises the agent to convert {src} into {tgt} only, up to {money(intent.maxBudget, src)} in total and{" "}
        {money(intent.maxTransactionAmount, src)} per conversion
        {intent.approvalThreshold ? `, asking you above ${money(intent.approvalThreshold, src)}` :
          intent.executionMode === "HUMAN_IN_THE_LOOP" ? ", asking you before every conversion" : ""}
        , until {formatDate(intent.deadline)}. Every conversion is checked by the policy engine; you can pause or cancel at any time.
      </p>
      {error && <p className="mt-3 text-sm text-deny" role="alert">{error}</p>}
      <div className="mt-4 flex flex-wrap gap-3">
        <button className="btn-allow" onClick={() => submit(true)} disabled={busy !== null}>
          {busy === "activate" ? "Activating…" : "Activate agent"}
        </button>
        <button className="btn-quiet" onClick={() => submit(false)} disabled={busy !== null}>
          {busy === "draft" ? "Saving…" : "Save as draft"}
        </button>
      </div>
    </section>
  );
}
