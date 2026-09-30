"use client";

import { PageHeader } from "@/components/Shell";
import { Timeline } from "@/components/Timeline";
import { usePoll } from "@/lib/api";
import type { AuditView } from "@/lib/types";

export default function ActivityPage() {
  const { data, error } = usePoll<AuditView[]>("/activity?limit=100", 4000);
  return (
    <>
      <PageHeader title="Activity"
                  subtitle="Every agent decision, policy evaluation, approval and conversion across your goals. Records are append-only." />
      {error && <p className="text-sm text-deny">{error.message}</p>}
      <section className="panel p-5">
        {data ? <Timeline events={data} /> : <p className="text-sm text-ink-soft">Loading…</p>}
      </section>
    </>
  );
}
