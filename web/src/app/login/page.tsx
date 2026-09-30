"use client";

import { useState } from "react";

import { AgentGlyph } from "@/components/AgentVoice";

export default function LoginPage() {
  const [email, setEmail] = useState("demo@agentic.fx");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const response = await fetch("/api/auth/login", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ email, password }),
    });
    setBusy(false);
    if (response.ok) {
      window.location.href = "/dashboard";
    } else {
      const body = await response.json().catch(() => ({}));
      setError(body.detail ?? "Sign-in failed.");
    }
  }

  return (
    <main className="grid min-h-screen place-items-center px-4">
      <div className="w-full max-w-sm">
        <div className="mb-8 flex items-center gap-2 text-ink">
          <AgentGlyph className="h-6 w-6 text-agent" />
          <span className="text-lg font-semibold">Multi-Currency Agent</span>
        </div>
        <form onSubmit={submit} className="panel space-y-4 p-6">
          <h1 className="text-xl font-semibold">Sign in</h1>
          <label className="block space-y-1">
            <span className="label">Email</span>
            <input className="field" type="email" autoComplete="username" value={email}
                   onChange={(e) => setEmail(e.target.value)} required />
          </label>
          <label className="block space-y-1">
            <span className="label">Password</span>
            <input className="field" type="password" autoComplete="current-password" value={password}
                   onChange={(e) => setPassword(e.target.value)} required />
          </label>
          {error && <p className="text-sm text-deny" role="alert">{error}</p>}
          <button className="btn-primary w-full" disabled={busy}>
            {busy ? "Signing in…" : "Sign in"}
          </button>
          <p className="text-xs text-ink-faint">Demo account: demo@agentic.fx / demo-password</p>
        </form>
      </div>
    </main>
  );
}
