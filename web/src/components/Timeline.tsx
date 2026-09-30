import { AgentGlyph } from "@/components/AgentVoice";
import { DecisionStamp, Stamp } from "@/components/Stamp";
import { formatDateTime } from "@/lib/format";
import type { AuditView } from "@/lib/types";

const AGENT_TYPES = new Set(["MARKET_CONDITION_DETECTED", "EXECUTION_PROPOSED", "AGENT_WAITING"]);

const TITLES: Record<string, string> = {
  GOAL_CREATED: "Goal drafted",
  MANDATE_DRAFTED: "Mandate drafted",
  MANDATE_ACTIVATED: "Mandate activated",
  MANDATE_SUPERSEDED: "Mandate superseded",
  GOAL_ACTIVATED: "Agent activated",
  GOAL_PAUSED: "Agent paused",
  GOAL_RESUMED: "Agent resumed",
  GOAL_CANCELLED: "Goal cancelled",
  GOAL_EXPIRED: "Goal expired",
  GOAL_COMPLETED: "Goal completed",
  MARKET_CONDITION_DETECTED: "Market condition detected",
  AGENT_WAITING: "Agent waiting",
  EXECUTION_PROPOSED: "Agent proposed conversion",
  POLICY_EVALUATED: "Policy evaluation",
  APPROVAL_REQUESTED: "Approval requested",
  APPROVAL_GRANTED: "You approved",
  APPROVAL_REJECTED: "You rejected",
  APPROVAL_EXPIRED: "Approval expired",
  QUOTE_REVALIDATED: "Quote revalidated",
  CONVERSION_STARTED: "Conversion started",
  CONVERSION_COMPLETED: "Conversion executed",
  CONVERSION_FAILED: "Conversion failed",
};

/** Removes the parts of a summary that the title or the stamp already say. */
function body(event: AuditView): string {
  return event.summary
    .replace(/^Market condition detected\.\s*/, "")
    .replace(/^Policy evaluation: \w+\.\s*(Allowed\.|Needs your approval\.|Denied\.)?\s*/, "");
}

type Cycle = { key: string; agent: AuditView[]; authority: AuditView[] };

/**
 * Groups events into decision cycles: a market signal, the proposal it produced, and everything the policy engine,
 * the user and the execution engine did with it. Lifecycle events form their own single-lane cycles.
 */
export function cycles(events: AuditView[]): Cycle[] {
  type Open = Cycle & { proposalId: string | null; awaitingProposal: boolean; lifecycle: boolean };
  const out: Open[] = [];
  for (const e of events) {
    const agent = AGENT_TYPES.has(e.type);
    const current = out.at(-1);
    let target: Open | undefined;
    if (current && e.proposalId && (current.proposalId === e.proposalId || current.awaitingProposal)) {
      target = current; // same decision cycle
    } else if (current && !e.proposalId && !agent && current.lifecycle) {
      target = current; // consecutive goal/mandate lifecycle events
    }
    if (!target) {
      target = { key: e.id, agent: [], authority: [], proposalId: null, awaitingProposal: false,
                 lifecycle: !e.proposalId && !agent };
      out.push(target);
    }
    target.proposalId = e.proposalId ?? target.proposalId;
    target.awaitingProposal = e.type === "MARKET_CONDITION_DETECTED" && !target.proposalId;
    (agent ? target.agent : target.authority).push(e);
  }
  return out;
}

/** Two-lane audit timeline: what the agent reasoned (left) versus what was authorised and done (right). */
export function Timeline({ events, newestFirst = false }: { events: AuditView[]; newestFirst?: boolean }) {
  const chronological = newestFirst ? events : [...events].reverse();
  const rows = cycles(chronological);
  if (newestFirst) rows.reverse();
  if (rows.length === 0) {
    return <p className="text-sm text-ink-soft">Nothing has happened yet.</p>;
  }
  return (
    <div className="space-y-3">
      <div className="hidden grid-cols-2 gap-4 text-xs font-semibold text-ink-soft md:grid">
        <span className="flex items-center gap-1.5 text-agent">
          <AgentGlyph /> Agent reasoning
        </span>
        <span>Authority and execution</span>
      </div>
      <ol className="space-y-3">
        {rows.map((row) => (
          <li key={row.key} className="grid gap-2 md:grid-cols-2 md:gap-4">
            <div className="space-y-2">{row.agent.map((e) => <Entry key={e.id} event={e} agent />)}</div>
            <div className="space-y-2">{row.authority.map((e) => <Entry key={e.id} event={e} agent={false} />)}</div>
          </li>
        ))}
      </ol>
    </div>
  );
}

function Entry({ event, agent }: { event: AuditView; agent: boolean }) {
  const text = body(event);
  return (
    <div className={`rounded-panel border px-3 py-2 ${agent ? "border-agent-line bg-agent-soft" : "border-rule bg-paper"}`}>
      <div className="flex flex-wrap items-baseline justify-between gap-x-3">
        <span className={`text-sm font-semibold ${agent ? "text-agent" : "text-ink"}`}>{TITLES[event.type] ?? event.type}</span>
        <time className="text-xs text-ink-faint" dateTime={event.timestamp}>{formatDateTime(event.timestamp)}</time>
      </div>
      {event.policyDecision && event.type === "POLICY_EVALUATED" && (
        <div className="mt-1.5">
          <DecisionStamp decision={event.policyDecision} codes={event.reasonCodes} />
        </div>
      )}
      {event.type === "CONVERSION_COMPLETED" && event.requestedAmount && event.executedAmount && (
        <div className="mt-1.5">
          <Stamp tone="allow">
            {event.requestedAmount.display} → {event.executedAmount.display}
          </Stamp>
        </div>
      )}
      {text && <p className="mt-1 text-xs leading-relaxed text-ink-soft">{text}</p>}
      {event.mandateVersion != null && (
        <p className="mt-1 text-[11px] text-ink-faint">Under mandate v{event.mandateVersion}, by {event.actor}</p>
      )}
    </div>
  );
}
