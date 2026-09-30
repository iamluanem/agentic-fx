import { MODE_TEXT } from "@/lib/format";
import type { MandateView } from "@/lib/types";
import { StatusStamp } from "@/components/Stamp";

/** What the agent is authorised to do. Rendered on the authority side of the design. */
export function MandateCard({ mandate }: { mandate: MandateView }) {
  const p = mandate.permissions;
  const rows: [string, string][] = [
    ["Spend at most", p.maxTotalSpend.display],
    ["Per conversion", `up to ${p.maxTransactionAmount.display}`],
    [
      "Your approval",
      p.approvalThreshold
        ? `above ${p.approvalThreshold.display}`
        : mandate.executionMode === "HUMAN_IN_THE_LOOP"
          ? "for every conversion"
          : "never required",
    ],
    ["Split into parts", p.allowPartialExecution ? "Allowed" : "Single conversion only"],
    ["Execution", MODE_TEXT[mandate.executionMode]],
    ["Valid until", mandate.validUntil],
  ];
  return (
    <div className="panel p-4">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold">Mandate v{mandate.version}</h3>
        <StatusStamp status={mandate.status} />
      </div>
      <dl className="mt-3 divide-y divide-rule text-sm">
        {rows.map(([k, v]) => (
          <div key={k} className="flex justify-between gap-4 py-1.5">
            <dt className="text-ink-soft">{k}</dt>
            <dd className="text-right font-medium">{v}</dd>
          </div>
        ))}
      </dl>
      <p className="mt-3 text-xs text-ink-faint">
        {p.sourceCurrency} → {p.targetCurrency} only. The agent cannot change these terms; every conversion is checked
        against them by the policy engine.
      </p>
    </div>
  );
}
