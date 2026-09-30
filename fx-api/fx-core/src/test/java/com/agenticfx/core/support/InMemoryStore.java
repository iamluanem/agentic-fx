package com.agenticfx.core.support;

import com.agenticfx.core.account.CurrencyAccount;
import com.agenticfx.core.agent.AgentDecision;
import com.agenticfx.core.approval.ApprovalRequest;
import com.agenticfx.core.approval.ApprovalStatus;
import com.agenticfx.core.audit.AuditEvent;
import com.agenticfx.core.event.DomainEvent;
import com.agenticfx.core.execution.ExecutionProposal;
import com.agenticfx.core.execution.ExecutionStatus;
import com.agenticfx.core.fx.FxConversion;
import com.agenticfx.core.fx.FxQuote;
import com.agenticfx.core.goal.FxGoal;
import com.agenticfx.core.goal.GoalStatus;
import com.agenticfx.core.mandate.AgentMandate;
import com.agenticfx.core.mandate.MandateRef;
import com.agenticfx.core.mandate.MandateStatus;
import com.agenticfx.core.money.CurrencyPair;
import com.agenticfx.core.policy.PolicyDecision;
import com.agenticfx.core.port.EventPublisher;
import com.agenticfx.core.port.Repositories;
import com.agenticfx.core.port.Transactions;
import com.agenticfx.core.shared.ConflictException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * In-memory implementation of every port. Emulates the database: copies in and out, optimistic locking,
 * the schema's unique constraints, and transactional rollback (state is snapshotted at transaction start).
 * Transactions are serialised, like SERIALIZABLE isolation.
 */
public final class InMemoryStore implements Transactions, EventPublisher {

    private final ReentrantLock lock = new ReentrantLock();
    private int depth;

    private Map<UUID, FxGoal> goals = new HashMap<>();
    private Map<MandateRef, AgentMandate> mandates = new HashMap<>();
    private Map<UUID, ExecutionProposal> proposals = new HashMap<>();
    private Map<UUID, ApprovalRequest> approvals = new HashMap<>();
    private Map<UUID, CurrencyAccount> accounts = new HashMap<>();
    private List<FxConversion> conversions = new ArrayList<>();
    private List<FxQuote> quotes = new ArrayList<>();
    private List<PolicyDecision> evaluations = new ArrayList<>();
    private List<AgentDecision> decisions = new ArrayList<>();
    private List<AuditEvent> audit = new ArrayList<>();
    private List<DomainEvent> outbox = new ArrayList<>();

    // ------------------------------------------------------------------------- transactions

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        lock.lock();
        try {
            if (depth++ > 0) {
                return work.get();
            }
            var snapshot = new Snapshot();
            try {
                return work.get();
            } catch (RuntimeException e) {
                snapshot.restore();
                throw e;
            }
        } finally {
            depth--;
            lock.unlock();
        }
    }

    private final class Snapshot {
        final Map<UUID, FxGoal> g = new HashMap<>(goals);
        final Map<MandateRef, AgentMandate> m = new HashMap<>(mandates);
        final Map<UUID, ExecutionProposal> p = new HashMap<>(proposals);
        final Map<UUID, ApprovalRequest> a = new HashMap<>(approvals);
        final Map<UUID, CurrencyAccount> acc = new HashMap<>(accounts);
        final List<FxConversion> c = new ArrayList<>(conversions);
        final List<FxQuote> q = new ArrayList<>(quotes);
        final List<PolicyDecision> e = new ArrayList<>(evaluations);
        final List<AgentDecision> d = new ArrayList<>(decisions);
        final List<AuditEvent> au = new ArrayList<>(audit);
        final List<DomainEvent> o = new ArrayList<>(outbox);

        void restore() {
            goals = g; mandates = m; proposals = p; approvals = a; accounts = acc; conversions = c; quotes = q;
            evaluations = e; decisions = d; audit = au; outbox = o;
        }
    }

    private void requireTx() {
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Repository used outside a transaction");
        }
    }

    // ------------------------------------------------------------------------- events

    @Override
    public void publish(DomainEvent event) {
        requireTx();
        outbox.add(event);
    }

    public List<DomainEvent> outbox() {
        return List.copyOf(outbox);
    }

    public List<AuditEvent> auditTrail() {
        return List.copyOf(audit);
    }

    public List<FxQuote> quotes() {
        return List.copyOf(quotes);
    }

    // ------------------------------------------------------------------------- seeding

    public void seed(CurrencyAccount account) {
        inTransaction(() -> accounts.put(account.id(), Copies.of(account)));
    }

    public Repositories.Bundle repositories() {
        return new Repositories.Bundle(new GoalsRepo(), new MandatesRepo(), new ProposalsRepo(), new ApprovalsRepo(),
                new ConversionsRepo(), new AccountsRepo(), (quote, userId) -> { requireTx(); quotes.add(quote); },
                new EvaluationsRepo(), new DecisionsRepo(), new AuditRepo());
    }

    private static <K, V> void versionedUpdate(Map<K, V> map, K key, V value, Function<V, Long> version,
                                               Runnable bump, Function<V, V> copy) {
        V stored = map.get(key);
        if (stored == null || !version.apply(stored).equals(version.apply(value))) {
            throw new ConflictException("CONCURRENT_MODIFICATION", "Stale version for " + key);
        }
        bump.run();
        map.put(key, copy.apply(value));
    }

    // ------------------------------------------------------------------------- repositories

    private final class GoalsRepo implements Repositories.Goals {
        public void insert(FxGoal goal) { requireTx(); goals.put(goal.id(), Copies.of(goal)); }
        public void update(FxGoal goal) {
            requireTx();
            versionedUpdate(goals, goal.id(), goal, FxGoal::version, goal::versionIncremented, Copies::of);
        }
        public Optional<FxGoal> find(UUID id) { requireTx(); return Optional.ofNullable(goals.get(id)).map(Copies::of); }
        public List<FxGoal> findByUser(UUID userId) {
            requireTx();
            return goals.values().stream().filter(g -> g.userId().equals(userId)).map(Copies::of).toList();
        }
        public List<FxGoal> findActiveByPair(CurrencyPair pair) {
            requireTx();
            return goals.values().stream().filter(g -> g.status() == GoalStatus.ACTIVE && g.pair().equals(pair))
                    .map(Copies::of).toList();
        }
        public List<FxGoal> findUnfinished() {
            requireTx();
            return goals.values().stream().filter(g -> g.status() == GoalStatus.ACTIVE || g.status() == GoalStatus.PAUSED)
                    .map(Copies::of).toList();
        }
    }

    private final class MandatesRepo implements Repositories.Mandates {
        public void insert(AgentMandate m) {
            requireTx();
            if (mandates.containsKey(m.ref())) {
                throw new ConflictException("DUPLICATE", "Mandate version exists");
            }
            checkOneActive(m);
            mandates.put(m.ref(), Copies.of(m));
        }
        public void update(AgentMandate m) {
            requireTx();
            checkOneActive(m);
            versionedUpdate(mandates, m.ref(), m, AgentMandate::rowVersion, m::versionIncremented, Copies::of);
        }
        private void checkOneActive(AgentMandate m) {
            if (m.status() == MandateStatus.ACTIVE && mandates.values().stream().anyMatch(o ->
                    o.goalId().equals(m.goalId()) && o.status() == MandateStatus.ACTIVE && !o.ref().equals(m.ref()))) {
                throw new ConflictException("DUPLICATE", "ux_agent_mandates_one_active_per_goal");
            }
        }
        public Optional<AgentMandate> find(MandateRef ref) {
            requireTx();
            return Optional.ofNullable(mandates.get(ref)).map(Copies::of);
        }
        public Optional<AgentMandate> findActive(UUID goalId) {
            return findAll(goalId).stream().filter(m -> m.status() == MandateStatus.ACTIVE).findFirst();
        }
        public Optional<AgentMandate> findLatest(UUID goalId) {
            return findAll(goalId).stream().max(Comparator.comparingInt(AgentMandate::version));
        }
        public List<AgentMandate> findAll(UUID goalId) {
            requireTx();
            return mandates.values().stream().filter(m -> m.goalId().equals(goalId))
                    .sorted(Comparator.comparingInt(AgentMandate::version)).map(Copies::of).toList();
        }
    }

    private final class ProposalsRepo implements Repositories.Proposals {
        public void insert(ExecutionProposal p) {
            requireTx();
            if (proposals.values().stream().anyMatch(o -> o.userId().equals(p.userId())
                    && o.idempotencyKey().equals(p.idempotencyKey()))) {
                throw new ConflictException("DUPLICATE", "idempotency key");
            }
            checkOneOpen(p);
            proposals.put(p.id(), Copies.of(p));
        }
        public void update(ExecutionProposal p) {
            requireTx();
            checkOneOpen(p);
            versionedUpdate(proposals, p.id(), p, ExecutionProposal::version, p::versionIncremented, Copies::of);
        }
        private void checkOneOpen(ExecutionProposal p) {
            if (isOpen(p) && proposals.values().stream().anyMatch(o ->
                    o.goalId().equals(p.goalId()) && isOpen(o) && !o.id().equals(p.id()))) {
                throw new ConflictException("PROPOSAL_ALREADY_OPEN", "ux_execution_proposals_one_open_per_goal");
            }
        }
        private boolean isOpen(ExecutionProposal p) {
            return p.status() == ExecutionStatus.PROPOSED || p.status().isInFlight();
        }
        public Optional<ExecutionProposal> find(UUID id) {
            requireTx();
            return Optional.ofNullable(proposals.get(id)).map(Copies::of);
        }
        public Optional<ExecutionProposal> findByIdempotencyKey(UUID userId, String key) {
            requireTx();
            return proposals.values().stream().filter(p -> p.userId().equals(userId) && p.idempotencyKey().equals(key))
                    .findFirst().map(Copies::of);
        }
        public Optional<ExecutionProposal> findOpen(UUID goalId) {
            return findByGoal(goalId).stream().filter(this::isOpen).findFirst();
        }
        public List<ExecutionProposal> findByGoal(UUID goalId) {
            requireTx();
            return proposals.values().stream().filter(p -> p.goalId().equals(goalId))
                    .sorted(Comparator.comparing(ExecutionProposal::createdAt)).map(Copies::of).toList();
        }
        public List<ExecutionProposal> findExecutingSince(Instant before) {
            requireTx();
            return proposals.values().stream()
                    .filter(p -> p.status() == ExecutionStatus.EXECUTING && !p.updatedAt().isAfter(before))
                    .map(Copies::of).toList();
        }
        public Optional<Instant> lastRejectionAt(UUID goalId) {
            return findByGoal(goalId).stream()
                    .filter(p -> p.status() == ExecutionStatus.REJECTED && p.rejectionSource()
                            .map(s -> s == com.agenticfx.core.execution.RejectionSource.POLICY
                                    || s == com.agenticfx.core.execution.RejectionSource.USER).orElse(false))
                    .map(ExecutionProposal::updatedAt).max(Comparator.naturalOrder());
        }
    }

    private final class ApprovalsRepo implements Repositories.Approvals {
        public void insert(ApprovalRequest a) {
            requireTx();
            if (approvals.values().stream().anyMatch(o -> o.proposalId().equals(a.proposalId())
                    && o.status() == ApprovalStatus.PENDING)) {
                throw new ConflictException("DUPLICATE", "ux_approval_requests_one_pending");
            }
            approvals.put(a.id(), Copies.of(a));
        }
        public void update(ApprovalRequest a) {
            requireTx();
            versionedUpdate(approvals, a.id(), a, ApprovalRequest::version, a::versionIncremented, Copies::of);
        }
        public Optional<ApprovalRequest> find(UUID id) {
            requireTx();
            return Optional.ofNullable(approvals.get(id)).map(Copies::of);
        }
        public Optional<ApprovalRequest> findPendingByProposal(UUID proposalId) {
            requireTx();
            return approvals.values().stream()
                    .filter(a -> a.proposalId().equals(proposalId) && a.status() == ApprovalStatus.PENDING)
                    .findFirst().map(Copies::of);
        }
        public List<ApprovalRequest> findByUser(UUID userId, boolean pendingOnly) {
            requireTx();
            return approvals.values().stream().filter(a -> a.userId().equals(userId))
                    .filter(a -> !pendingOnly || a.status() == ApprovalStatus.PENDING).map(Copies::of).toList();
        }
        public List<ApprovalRequest> findPendingExpiredAt(Instant now) {
            requireTx();
            return approvals.values().stream().filter(a -> a.status() == ApprovalStatus.PENDING && a.isExpiredAt(now))
                    .map(Copies::of).toList();
        }
    }

    private final class ConversionsRepo implements Repositories.Conversions {
        public void insert(FxConversion c) {
            requireTx();
            if (conversions.stream().anyMatch(o -> o.proposalId().equals(c.proposalId()))) {
                throw new ConflictException("DUPLICATE", "fx_conversions.proposal_id");
            }
            conversions.add(c);
        }
        public Optional<FxConversion> findByProposal(UUID proposalId) {
            requireTx();
            return conversions.stream().filter(c -> c.proposalId().equals(proposalId)).findFirst();
        }
        public List<FxConversion> findByGoal(UUID goalId) {
            requireTx();
            return conversions.stream().filter(c -> c.goalId().equals(goalId)).toList();
        }
        public List<FxConversion> findByUser(UUID userId) {
            requireTx();
            return conversions.stream().filter(c -> c.userId().equals(userId)).toList();
        }
    }

    private final class AccountsRepo implements Repositories.Accounts {
        public Optional<CurrencyAccount> findByUser(UUID userId) {
            requireTx();
            return accounts.values().stream().filter(a -> a.userId().equals(userId)).findFirst().map(Copies::of);
        }
        public void update(CurrencyAccount a) {
            requireTx();
            versionedUpdate(accounts, a.id(), a, CurrencyAccount::version, a::versionIncremented, Copies::of);
        }
    }

    private final class EvaluationsRepo implements Repositories.PolicyEvaluations {
        public void insert(PolicyDecision d, UUID quoteId) { requireTx(); evaluations.add(d); }
        public List<PolicyDecision> findByProposal(UUID proposalId) {
            requireTx();
            return evaluations.stream().filter(e -> e.proposalId().equals(proposalId)).toList();
        }
    }

    private final class DecisionsRepo implements Repositories.AgentDecisions {
        public void insert(AgentDecision d) { requireTx(); decisions.add(d); }
        public List<AgentDecision> findByGoal(UUID goalId, int limit) {
            requireTx();
            List<AgentDecision> all = new ArrayList<>(decisions.stream().filter(d -> d.goalId().equals(goalId)).toList());
            java.util.Collections.reverse(all);
            return all.stream().limit(limit).toList();
        }
    }

    private final class AuditRepo implements Repositories.AuditLog {
        public void append(AuditEvent e) { requireTx(); audit.add(e); }
        public List<AuditEvent> findByGoal(UUID goalId) {
            requireTx();
            return audit.stream().filter(e -> goalId.equals(e.goalId())).toList();
        }
        public List<AuditEvent> findByUser(UUID userId, int limit) {
            requireTx();
            List<AuditEvent> all = new ArrayList<>(audit.stream().filter(e -> e.userId().equals(userId)).toList());
            java.util.Collections.reverse(all);
            return all.stream().limit(limit).toList();
        }
    }
}
