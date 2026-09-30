import { REASON_TEXT } from "@/lib/format";

type Tone = "allow" | "approval" | "deny" | "neutral" | "agent";

const TONES: Record<Tone, string> = {
  allow: "border-allow/50 bg-allow-soft text-allow",
  approval: "border-approval/50 bg-approval-soft text-approval",
  deny: "border-deny/50 bg-deny-soft text-deny",
  agent: "border-agent-line bg-agent-soft text-agent",
  neutral: "border-rule bg-canvas text-ink-soft",
};

/** Authority marks: a bordered stamp, visually distinct from the agent's voice. */
export function Stamp({ tone, children }: { tone: Tone; children: React.ReactNode }) {
  return (
    <span className={`inline-flex items-center gap-1 rounded-stamp border px-2 py-0.5 text-xs font-semibold ${TONES[tone]}`}>
      {children}
    </span>
  );
}

export function decisionTone(decision: string | null | undefined): Tone {
  if (decision === "ALLOW") return "allow";
  if (decision === "REQUIRE_APPROVAL") return "approval";
  if (decision === "DENY") return "deny";
  return "neutral";
}

const DECISION_TEXT: Record<string, string> = {
  ALLOW: "Allowed by policy",
  REQUIRE_APPROVAL: "Needs your approval",
  DENY: "Denied by policy",
};

export function DecisionStamp({ decision, codes = [] }: { decision: string; codes?: string[] }) {
  return (
    <span className="inline-flex flex-wrap items-center gap-1.5">
      <Stamp tone={decisionTone(decision)}>{DECISION_TEXT[decision] ?? decision}</Stamp>
      {codes.map((code) => (
        <span key={code} className="text-xs text-ink-soft" title={code}>
          {REASON_TEXT[code] ?? code}
        </span>
      ))}
    </span>
  );
}

const STATUS_TONE: Record<string, Tone> = {
  ACTIVE: "allow",
  COMPLETED: "allow",
  PAUSED: "approval",
  AWAITING_APPROVAL: "approval",
  PENDING: "approval",
  EXECUTING: "agent",
  PROPOSED: "agent",
  DRAFT: "neutral",
  APPROVED: "allow",
  REJECTED: "deny",
  FAILED: "deny",
  CANCELLED: "neutral",
  EXPIRED: "neutral",
};

export function StatusStamp({ status }: { status: string }) {
  const text = status.charAt(0) + status.slice(1).toLowerCase().replaceAll("_", " ");
  return <Stamp tone={STATUS_TONE[status] ?? "neutral"}>{text}</Stamp>;
}
