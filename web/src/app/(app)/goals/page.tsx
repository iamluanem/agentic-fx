"use client";

import Link from "next/link";

import { PageHeader } from "@/components/Shell";
import { StatusStamp } from "@/components/Stamp";
import { usePoll } from "@/lib/api";
import { formatDate, STRATEGY_TEXT } from "@/lib/format";
import type { GoalView } from "@/lib/types";

export default function GoalsPage() {
  const { data: goals, error } = usePoll<GoalView[]>("/goals", 5000);
  return (
    <>
      <PageHeader title="Goals" subtitle="Each goal has its own mandate: what the agent may do, and nothing more."
                  actions={<Link href="/goals/new" className="btn-primary">New goal</Link>} />
      {error && <p className="text-sm text-deny">{error.message}</p>}
      {goals?.length === 0 && (
        <div className="panel p-8 text-sm text-ink-soft">
          No goals yet. <Link href="/goals/new" className="font-semibold text-agent">Describe your first goal</Link>.
        </div>
      )}
      <ul className="space-y-3">
        {goals?.map((g) => (
          <li key={g.id}>
            <Link href={`/goals/${g.id}`} className="panel grid gap-3 p-4 hover:border-ink-faint sm:grid-cols-[1.5fr_1fr_1fr_auto] sm:items-center">
              <div>
                <p className="font-semibold">{g.name}</p>
                <p className="text-xs text-ink-soft">{STRATEGY_TEXT[g.strategy.type]}, deadline {formatDate(g.deadline)}</p>
              </div>
              <div className="text-sm">
                {g.acquired.display} <span className="text-ink-faint">/ {g.targetAmount.display}</span>
                <div className="mt-1 h-1.5 rounded-full bg-canvas">
                  <div className="h-full rounded-full bg-allow" style={{ width: `${Math.min(100, g.progress * 100)}%` }} />
                </div>
              </div>
              <p className="text-sm text-ink-soft">Spent {g.spent.display} of {g.maxBudget.display}</p>
              <StatusStamp status={g.status} />
            </Link>
          </li>
        ))}
      </ul>
    </>
  );
}
