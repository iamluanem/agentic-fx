"use client";

import { ApprovalCard } from "@/components/ApprovalCard";
import { PageHeader } from "@/components/Shell";
import { usePoll } from "@/lib/api";
import type { ApprovalView } from "@/lib/types";

export default function ApprovalsPage() {
  const { data, error, reload } = usePoll<ApprovalView[]>("/approvals", 3000);
  return (
    <>
      <PageHeader title="Approvals"
                  subtitle="Conversions your mandate says need your decision. Approving re-checks the quote and the policy before any money moves." />
      {error && <p className="text-sm text-deny">{error.message}</p>}
      {data?.length === 0 && (
        <div className="panel p-8 text-sm text-ink-soft">Nothing is waiting for you. The agent will ask here when a conversion is above your threshold.</div>
      )}
      <div className="space-y-4">
        {data?.map((a) => <ApprovalCard key={a.id} approval={a} onDecided={reload} />)}
      </div>
    </>
  );
}
