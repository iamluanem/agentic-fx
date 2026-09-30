// Mirrors fx-api response views (fx-app/.../web/dto/Views.java) and agent-service models.

export type MoneyView = { amount: number; currency: string; display: string };

export type GoalView = {
  id: string;
  name: string;
  sourceCurrency: string;
  targetCurrency: string;
  targetAmount: MoneyView;
  acquired: MoneyView;
  spent: MoneyView;
  maxBudget: MoneyView;
  remainingTarget: MoneyView;
  remainingBudget: MoneyView;
  deadline: string;
  strategy: { type: string; targetRate: number | null; plannedTranches: number; minImprovementBps: number };
  status: "DRAFT" | "ACTIVE" | "PAUSED" | "COMPLETED" | "CANCELLED" | "EXPIRED";
  progress: number;
  createdAt: string;
  updatedAt: string;
};

export type MandateView = {
  mandateId: string;
  goalId: string;
  version: number;
  status: string;
  executionMode: "HUMAN_IN_THE_LOOP" | "AUTONOMOUS";
  permissions: {
    sourceCurrency: string;
    targetCurrency: string;
    maxTotalSpend: MoneyView;
    maxTransactionAmount: MoneyView;
    approvalThreshold: MoneyView | null;
    allowPartialExecution: boolean;
  };
  validUntil: string;
  createdAt: string;
  activatedAt: string | null;
  endedAt: string | null;
};

export type QuoteView = {
  id: string;
  pair: string;
  rate: number;
  sourceAmount: MoneyView;
  fee: MoneyView;
  targetAmount: MoneyView;
  provider: string;
  quotedAt: string;
  expiresAt: string;
};

export type ExplanationView = {
  whatHappened: string;
  why: string;
  amountToConvert: MoneyView;
  expectedAmount: MoneyView;
  fee: MoneyView;
  rate: number;
  remainingTargetAfter: MoneyView;
  mandateRule: string;
  narrative: string;
};

export type ProposalView = {
  id: string;
  goalId: string;
  status: string;
  origin: "STRATEGY_ENGINE" | "LLM_AGENT";
  mandateId: string;
  mandateVersion: number;
  quote: QuoteView;
  explanation: ExplanationView | null;
  rejectionSource: string | null;
  statusReason: string | null;
  createdAt: string;
  updatedAt: string;
};

export type PolicyView = {
  decision: "ALLOW" | "REQUIRE_APPROVAL" | "DENY";
  reasonCodes: string[];
  explanation: string;
  policyVersion: string;
  evaluatedAt: string;
};

export type ConversionView = {
  id: string;
  proposalId: string;
  goalId: string;
  pair: string;
  rate: number;
  sourceAmount: MoneyView;
  fee: MoneyView;
  targetAmount: MoneyView;
  provider: string;
  providerReference: string;
  mandateId: string;
  mandateVersion: number;
  executedAt: string;
};

export type ApprovalView = {
  id: string;
  status: string;
  goalId: string;
  goalName: string | null;
  requestedAt: string;
  expiresAt: string;
  decidedAt: string | null;
  note: string | null;
  proposal: ProposalView | null;
  policy: PolicyView | null;
};

export type AuditView = {
  id: string;
  timestamp: string;
  type: string;
  actor: string;
  summary: string;
  goalId: string | null;
  mandateId: string | null;
  mandateVersion: number | null;
  proposalId: string | null;
  agentDecision: string | null;
  policyDecision: PolicyView["decision"] | null;
  reasonCodes: string[];
  quoteId: string | null;
  quoteRate: string | null;
  provider: string | null;
  requestedAmount: MoneyView | null;
  executedAmount: MoneyView | null;
  correlationId: string | null;
};

export type DecisionView = { id: string; type: string; source: string; summary: string; proposalId: string | null; decidedAt: string };

export type GoalDetailView = {
  goal: GoalView;
  mandate: MandateView | null;
  mandateVersions: MandateView[];
  agent: { state: string; message: string };
  openProposal: ProposalView | null;
  pendingApprovalId: string | null;
  recentDecisions: DecisionView[];
  conversions: ConversionView[];
};

export type OutcomeView = {
  proposal: ProposalView | null;
  policy: PolicyView | null;
  approvalId: string | null;
  approvalStatus: string | null;
  conversion: ConversionView | null;
};

export type RateView = { pair: string; rate: number; reference: number; changeBps: number; observedAt: string };
export type BalanceView = { currency: string; available: MoneyView };
export type MeView = { id: string; email: string; displayName: string; roles: string[] };

// ----------------------------------------------------------------- agent-service

export type StructuredIntent = {
  name: string | null;
  sourceCurrency: string | null;
  targetCurrency: string | null;
  targetAmount: string | null;
  deadline: string | null;
  maxBudget: string | null;
  targetRate: string | null;
  maxTransactionAmount: string | null;
  approvalThreshold: string | null;
  allowPartialExecution: boolean | null;
  executionMode: "HUMAN_IN_THE_LOOP" | "AUTONOMOUS" | null;
  strategyType: "TARGET_RATE" | "ACCUMULATE" | "DEADLINE_AWARE" | null;
};

export type IntentResponse = {
  status: "READY" | "NEEDS_INPUT";
  intent: StructuredIntent;
  missing: string[];
  questions: string[];
  defaultsApplied: { field: string; value: unknown; reason: string }[];
  warnings: string[];
  insights: string[];
  reply: string;
  parser: "anthropic" | "deterministic";
  language: "en" | "pt";
};

export type AdviseResponse = {
  reply: string;
  actions: { tool: string; arguments: Record<string, unknown>; ok: boolean; summary: string }[];
  proposal: OutcomeView | null;
  agent: "anthropic" | "deterministic";
};
