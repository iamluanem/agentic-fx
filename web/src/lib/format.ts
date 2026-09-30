export function formatDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso.length === 10 ? `${iso}T12:00:00` : iso);
  return d.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" });
}

export function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

export function formatDateTime(iso: string): string {
  const d = new Date(iso);
  return `${d.toLocaleDateString("en-GB", { day: "numeric", month: "short" })} ${formatTime(iso)}`;
}

export function daysUntil(isoDate: string): number {
  const end = new Date(`${isoDate}T23:59:59`);
  return Math.max(0, Math.ceil((end.getTime() - Date.now()) / 86_400_000));
}

/** "BRL_EUR" 0.165 → "1 BRL = 0.1650 EUR · 1 EUR = R$ 6.0606" style parts. */
export function describeRate(pair: string, rate: number) {
  const [source, target] = pair.split("_");
  return {
    direct: `1 ${source} = ${rate.toFixed(4)} ${target}`,
    inverse: `1 ${target} = ${(1 / rate).toFixed(4)} ${source}`,
  };
}

export const REASON_TEXT: Record<string, string> = {
  WITHIN_MANDATE_LIMITS: "Within mandate limits",
  WITHIN_APPROVAL_THRESHOLD: "Below approval threshold",
  AUTONOMOUS_EXECUTION_PERMITTED: "Autonomous mode",
  HUMAN_APPROVAL_GRANTED: "Approved by you",
  TRANSACTION_ABOVE_APPROVAL_THRESHOLD: "Above approval threshold",
  HUMAN_IN_THE_LOOP_REQUIRES_APPROVAL: "Every conversion needs approval",
  RATE_MOVED_SINCE_APPROVAL: "Rate moved since approval",
  MAX_TRANSACTION_AMOUNT_EXCEEDED: "Above per-transaction limit",
  MAX_TOTAL_SPEND_EXCEEDED: "Above total spend limit",
  GOAL_BUDGET_EXCEEDED: "Above goal budget",
  INSUFFICIENT_BALANCE: "Insufficient balance",
  QUOTE_EXPIRED: "Quote expired",
  MANDATE_NOT_ACTIVE: "Mandate not active",
  MANDATE_EXPIRED: "Mandate expired",
  MANDATE_REFERENCE_MISMATCH: "Superseded mandate version",
  GOAL_NOT_ACTIVE: "Goal not active",
  DEADLINE_PASSED: "Deadline passed",
  GOAL_ALREADY_REACHED: "Goal already reached",
  EXCEEDS_REMAINING_TARGET: "More than remaining target",
  PARTIAL_EXECUTION_NOT_ALLOWED: "Partial conversions not allowed",
  SOURCE_CURRENCY_NOT_ALLOWED: "Currency not allowed",
  TARGET_CURRENCY_NOT_ALLOWED: "Currency not allowed",
  PROPOSAL_NOT_EVALUABLE: "Proposal already processed",
};

export const MODE_TEXT: Record<string, string> = {
  HUMAN_IN_THE_LOOP: "Human approval",
  AUTONOMOUS: "Autonomous",
};

export const STRATEGY_TEXT: Record<string, string> = {
  ACCUMULATE: "Progressive accumulation",
  TARGET_RATE: "Target rate",
  DEADLINE_AWARE: "Deadline-aware",
};
