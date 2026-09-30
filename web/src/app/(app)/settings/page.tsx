"use client";

import { useState } from "react";

import { PageHeader } from "@/components/Shell";
import { ApiError, fxPost, usePoll } from "@/lib/api";
import { describeRate } from "@/lib/format";
import type { MeView, RateView } from "@/lib/types";

const CAN = [
  "Watch the market and decide when a conversion looks favourable",
  "Size conversions within your mandate",
  "Propose conversions and explain why",
  "Ask you to approve, even when the mandate would not require it",
];
const CANNOT = [
  "Change or activate a mandate",
  "Approve its own proposals",
  "Execute a conversion the policy engine has not allowed",
  "Use an expired quote or exceed any limit you set",
];

export default function SettingsPage() {
  const { data: me } = usePoll<MeView>("/me", 60000);
  return (
    <>
      <PageHeader title="Settings" />
      <div className="grid gap-6 lg:grid-cols-2">
        <section className="panel p-5">
          <h2 className="mb-3 text-sm font-semibold">Profile</h2>
          {me ? (
            <dl className="space-y-1 text-sm">
              <div className="flex justify-between"><dt className="text-ink-soft">Name</dt><dd>{me.displayName}</dd></div>
              <div className="flex justify-between"><dt className="text-ink-soft">Email</dt><dd>{me.email}</dd></div>
              <div className="flex justify-between"><dt className="text-ink-soft">Roles</dt><dd>{me.roles.join(", ")}</dd></div>
            </dl>
          ) : <p className="text-sm text-ink-soft">Loading…</p>}
        </section>

        <section className="panel p-5">
          <h2 className="mb-3 text-sm font-semibold">What the agent can and cannot do</h2>
          <div className="grid gap-4 text-sm sm:grid-cols-2">
            <ul className="space-y-1.5">
              {CAN.map((c) => <li key={c} className="text-ink-soft"><span className="text-allow">✓</span> {c}</li>)}
            </ul>
            <ul className="space-y-1.5">
              {CANNOT.map((c) => <li key={c} className="text-ink-soft"><span className="text-deny">✗</span> {c}</li>)}
            </ul>
          </div>
        </section>

        <MarketControls />
      </div>
    </>
  );
}

/** Demo controls: available only when fx-api runs with the dev profile (the endpoint does not exist otherwise). */
function MarketControls() {
  const { data: rates, reload } = usePoll<RateView[]>("/fx/rates", 3000);
  const [custom, setCustom] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const eur = rates?.find((r) => r.pair === "BRL_EUR");

  async function setRate(rate: number) {
    setMessage(null);
    try {
      await fxPost("/dev/fx/rates", { pair: "BRL_EUR", rate: Number(rate.toFixed(6)) });
      setMessage(`BRL → EUR set to ${rate.toFixed(5)}. The strategy engine reacts within a second.`);
      reload();
    } catch (e) {
      setMessage(e instanceof ApiError && e.status === 404
        ? "Market controls need fx-api running with the dev profile."
        : e instanceof Error ? e.message : "Could not set the rate.");
    }
  }

  async function failNext() {
    try {
      await fxPost("/dev/provider/fail-next");
      setMessage("The next conversion will fail at the provider, so you can see the refund path.");
    } catch {
      setMessage("Market controls need fx-api running with the dev profile.");
    }
  }

  return (
    <section className="panel p-5 lg:col-span-2">
      <h2 className="text-sm font-semibold">Demo market controls</h2>
      <p className="mt-1 text-sm text-ink-soft">
        Move the simulated BRL → EUR rate to trigger the agent. Changes are relative to the 60-minute reference rate.
      </p>
      {eur && (
        <p className="mt-3 text-sm">
          Now {describeRate(eur.pair, eur.rate).direct} ({describeRate(eur.pair, eur.rate).inverse}), reference{" "}
          {eur.reference.toFixed(5)}, {eur.changeBps >= 0 ? "+" : ""}{eur.changeBps.toFixed(1)} bps
        </p>
      )}
      <div className="mt-4 flex flex-wrap gap-2">
        {[
          { label: "Demo step 1: improve 1.15%", factor: 1.0115 },
          { label: "Demo step 2: improve 1.85%", factor: 1.0185 },
          { label: "Worsen 1.2%", factor: 0.988 },
          { label: "Back to reference", factor: 1 },
        ].map((b) => (
          <button key={b.label} className="btn-quiet" disabled={!eur} onClick={() => eur && setRate(eur.reference * b.factor)}>
            {b.label}
          </button>
        ))}
        <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); if (Number(custom) > 0) setRate(Number(custom)); }}>
          <label htmlFor="custom-rate" className="sr-only">Custom BRL to EUR rate</label>
          <input id="custom-rate" className="field w-32" inputMode="decimal" placeholder="0.16800" value={custom}
                 onChange={(e) => setCustom(e.target.value)} />
          <button className="btn-quiet">Set rate</button>
        </form>
        <button className="btn-deny" onClick={failNext}>Fail next conversion</button>
      </div>
      {message && <p className="mt-3 text-sm text-ink-soft" role="status">{message}</p>}
    </section>
  );
}
