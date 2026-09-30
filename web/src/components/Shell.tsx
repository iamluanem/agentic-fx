"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

import { AgentGlyph } from "@/components/AgentVoice";
import { usePoll } from "@/lib/api";
import type { ApprovalView } from "@/lib/types";

const NAV = [
  { href: "/dashboard", label: "Dashboard" },
  { href: "/goals", label: "Goals" },
  { href: "/approvals", label: "Approvals" },
  { href: "/activity", label: "Activity" },
  { href: "/settings", label: "Settings" },
];

export function Shell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const { data: approvals } = usePoll<ApprovalView[]>("/approvals", 4000);
  const pending = approvals?.length ?? 0;

  async function signOut() {
    await fetch("/api/auth/logout", { method: "POST" });
    window.location.href = "/login";
  }

  return (
    <div className="min-h-screen md:grid md:grid-cols-[220px_1fr]">
      <aside className="border-b border-rule bg-ink text-white md:sticky md:top-0 md:h-screen md:border-b-0">
        <div className="flex items-center gap-2 px-5 py-5">
          <AgentGlyph className="h-5 w-5 text-agent-line" />
          <span className="text-sm font-semibold leading-tight">
            Multi-Currency
            <br />
            Agent
          </span>
        </div>
        <nav className="flex gap-1 overflow-x-auto px-3 pb-3 md:flex-col md:pb-0" aria-label="Main">
          {NAV.map((item) => {
            const active = pathname === item.href || pathname.startsWith(`${item.href}/`);
            return (
              <Link
                key={item.href}
                href={item.href}
                aria-current={active ? "page" : undefined}
                className={`flex items-center justify-between whitespace-nowrap rounded-lg px-3 py-2 text-sm ${
                  active ? "bg-white/10 font-semibold" : "text-white/70 hover:bg-white/5 hover:text-white"
                }`}
              >
                {item.label}
                {item.href === "/approvals" && pending > 0 && (
                  <span className="ml-2 rounded-stamp bg-approval px-1.5 text-xs font-semibold text-white">{pending}</span>
                )}
              </Link>
            );
          })}
        </nav>
        <div className="hidden px-5 py-6 md:absolute md:bottom-0 md:block">
          <button onClick={signOut} className="text-xs text-white/60 hover:text-white">
            Sign out
          </button>
        </div>
      </aside>
      <main className="mx-auto w-full max-w-6xl px-4 py-6 md:px-8 md:py-8">{children}</main>
    </div>
  );
}

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: string; actions?: React.ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
        {subtitle && <p className="mt-1 max-w-2xl text-sm text-ink-soft">{subtitle}</p>}
      </div>
      {actions}
    </div>
  );
}
