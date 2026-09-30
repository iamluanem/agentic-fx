import type { ConversionView, GoalView } from "@/lib/types";

/**
 * Goal progress drawn as the actual conversions that built it: each segment is one executed conversion,
 * so the bar doubles as a record of how the agent accumulated the target.
 */
export function ProgressLedger({ goal, conversions }: { goal: GoalView; conversions: ConversionView[] }) {
  const target = goal.targetAmount.amount;
  const ordered = [...conversions].sort((a, b) => a.executedAt.localeCompare(b.executedAt));
  const pct = Math.min(100, goal.progress * 100);
  return (
    <div>
      <div className="flex items-baseline justify-between gap-4">
        <div className="text-3xl font-semibold tracking-tight">
          {goal.acquired.display}
          <span className="ml-2 text-base font-normal text-ink-faint">of {goal.targetAmount.display}</span>
        </div>
        <div className="text-sm font-semibold text-ink-soft">{pct.toFixed(1)}%</div>
      </div>
      <div
        className="mt-3 flex h-3 w-full overflow-hidden rounded-full bg-canvas"
        role="progressbar"
        aria-valuenow={Math.round(pct)}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label="Goal progress"
      >
        {ordered.map((c, i) => (
          <div
            key={c.id}
            title={`${c.sourceAmount.display} → ${c.targetAmount.display}`}
            className={`h-full ${i % 2 === 0 ? "bg-allow" : "bg-allow/75"} border-r-2 border-paper last:border-r-0`}
            style={{ width: `${Math.min(100, (c.targetAmount.amount / target) * 100)}%` }}
          />
        ))}
      </div>
      <div className="mt-2 flex flex-wrap justify-between gap-2 text-xs text-ink-soft">
        <span>
          {ordered.length} conversion{ordered.length === 1 ? "" : "s"}, {goal.spent.display} spent of {goal.maxBudget.display}
        </span>
        <span>{goal.remainingTarget.display} to go</span>
      </div>
    </div>
  );
}
