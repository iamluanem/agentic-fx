"use client";

import Link from "next/link";

import { AgentVoice } from "@/components/AgentVoice";
import { ProgressLedger } from "@/components/ProgressLedger";
import { RatesTable } from "@/components/RatesTable";
import { PageHeader } from "@/components/Shell";
import { StatusStamp } from "@/components/Stamp";
import { usePoll } from "@/lib/api";
import { daysUntil, formatDate, formatDateTime } from "@/lib/format";
import type { ApprovalView, BalanceView, GoalDetailView, GoalView, RateView } from "@/lib/types";

export default function DashboardPage() {
  const { data: balances } = usePoll<BalanceView[]>("/accounts/me/balances", 4000);
  const { data: goals } = usePoll<GoalView[]>("/goals", 5000);
  const { data: rates } = usePoll<RateView[]>("/fx/rates", 3000);
  const { data: approvals } = usePoll<ApprovalView[]>("/approvals", 3000);
  const active = goals?.find((g) => g.status === "ACTIVE") ?? goals?.find((g) => g.status === "PAUSED") ?? null;

  return (
    <>
      <PageHeader title="Dashboard" subtitle="Your balances, what the agent is working on, and anything waiting for you." />

      <section aria-label="Portfolio" className="panel mb-6 flex flex-wrap divide-x divide-rule">
        {(balances ?? []).map((b) => (
          <div key={b.currency} className="min-w-[140px] flex-1 px-5 py-4">
            <p className="label">{b.currency}</p>
            <p className="mt-1 whitespace-nowrap text-lg font-semibold tracking-tight sm:text-xl">{b.available.display}</p>
          </div>
        ))}
        {!balances && <p className="px-5 py-4 text-sm text-ink-soft">Loading balances…</p>}
      </section>

      {approvals && approvals.length > 0 && (
        <Link
          href="/approvals"
          className="mb-6 flex items-center justify-between rounded-panel border border-approval/40 bg-approval-soft px-5 py-3 text-sm"
        >
          <span>
            <strong className="text-approval">
              {approvals.length} conversion{approvals.length > 1 ? "s" : ""} waiting for your approval.
            </strong>{" "}
            {approvals[0].proposal?.quote.sourceAmount.display} → {approvals[0].proposal?.quote.targetAmount.display}
          </span>
          <span className="font-semibold text-approval">Review</span>
        </Link>
      )}

      <div className="grid gap-6 lg:grid-cols-[1.6fr_1fr]">
        {active ? <ActiveGoal goal={active} /> : <NoGoal hasGoals={!!goals?.length} />}
        <section className="panel p-5">
          <h2 className="mb-3 text-sm font-semibold">Market (simulated)</h2>
          {rates ? <RatesTable rates={rates} /> : <p className="text-sm text-ink-soft">Loading rates…</p>}
        </section>
      </div>
    </>
  );
}

function ActiveGoal({ goal }: { goal: GoalView }) {
  const { data } = usePoll<GoalDetailView>(`/goals/${goal.id}`, 3000);
  return (
    <section className="panel p-5">
      <div className="mb-4 flex flex-wrap items-start justify-between gap-2">
        <div>
          <h2 className="text-lg font-semibold">{goal.name}</h2>
          <p className="text-sm text-ink-soft">
            Deadline {formatDate(goal.deadline)}, {daysUntil(goal.deadline)} days left
          </p>
        </div>
        <StatusStamp status={goal.status} />
      </div>
      <ProgressLedger goal={data?.goal ?? goal} conversions={data?.conversions ?? []} />
      <div className="mt-5">
        <AgentVoice label="Agent status">{data?.agent.message ?? "Monitoring the market."}</AgentVoice>
      </div>
      {data && data.recentDecisions.length > 0 && (
        <div className="mt-5">
          <h3 className="mb-2 text-xs font-semibold text-ink-soft">Agent activity</h3>
          <ul className="space-y-2">
            {data.recentDecisions.slice(0, 4).map((d) => (
              <li key={d.id} className="flex gap-3 text-sm">
                <time className="w-28 shrink-0 text-xs text-ink-faint">{formatDateTime(d.decidedAt)}</time>
                <span className="text-ink-soft">{d.summary}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
      <Link href={`/goals/${goal.id}`} className="btn-quiet mt-5">
        Open goal
      </Link>
    </section>
  );
}

function NoGoal({ hasGoals }: { hasGoals: boolean }) {
  return (
    <section className="panel flex flex-col items-start justify-center gap-3 p-8">
      <h2 className="text-lg font-semibold">{hasGoals ? "No active goal" : "Give the agent its first goal"}</h2>
      <p className="max-w-md text-sm text-ink-soft">
        Describe what you need in your own words, for example “I need €5,000 for my Europe trip before October 20”. You
        review the mandate before anything happens.
      </p>
      <Link href="/goals/new" className="btn-primary">
        Create a goal
      </Link>
    </section>
  );
}
