/**
 * The agent's voice. Everything rendered here is advisory: observations, reasoning and proposals.
 * It never carries authority — decisions are rendered as stamps.
 */
export function AgentVoice({
  children,
  label = "Agent",
  compact = false,
}: {
  children: React.ReactNode;
  label?: string;
  compact?: boolean;
}) {
  return (
    <div className={`rounded-panel border-l-4 border-agent bg-agent-soft ${compact ? "px-3 py-2" : "px-4 py-3"}`}>
      <div className="mb-1 flex items-center gap-2 text-xs font-semibold text-agent">
        <AgentGlyph />
        {label}
      </div>
      <div className="text-sm leading-relaxed text-ink">{children}</div>
    </div>
  );
}

export function AgentGlyph({ className = "h-3.5 w-3.5" }: { className?: string }) {
  return (
    <svg viewBox="0 0 16 16" className={className} aria-hidden="true">
      <circle cx="8" cy="8" r="6.5" fill="none" stroke="currentColor" strokeWidth="1.5" />
      <circle cx="8" cy="8" r="2" fill="currentColor" />
    </svg>
  );
}
