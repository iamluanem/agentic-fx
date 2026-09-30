import { describeRate, formatTime } from "@/lib/format";
import type { RateView } from "@/lib/types";

export function RatesTable({ rates }: { rates: RateView[] }) {
  return (
    <table className="w-full text-sm">
      <thead>
        <tr className="text-left text-xs text-ink-soft">
          <th className="pb-2 font-medium">Pair</th>
          <th className="pb-2 text-right font-medium">Rate</th>
          <th className="pb-2 text-right font-medium">vs 60-min reference</th>
        </tr>
      </thead>
      <tbody className="divide-y divide-rule">
        {rates.map((r) => {
          const d = describeRate(r.pair, r.rate);
          const up = r.changeBps >= 0;
          return (
            <tr key={r.pair}>
              <td className="py-2">
                <div className="font-medium">{r.pair.replace("_", " → ")}</div>
                <div className="text-xs text-ink-faint">{d.inverse}</div>
              </td>
              <td className="py-2 text-right">
                {r.rate.toFixed(5)}
                <div className="text-xs text-ink-faint">{formatTime(r.observedAt)}</div>
              </td>
              <td className={`py-2 text-right font-medium ${up ? "text-allow" : "text-deny"}`}>
                {up ? "+" : ""}
                {r.changeBps.toFixed(1)} bps
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
