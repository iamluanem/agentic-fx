package com.agenticfx.app.persistence;

import com.agenticfx.core.agent.Explanation;
import com.agenticfx.core.execution.ExecutionProposal;
import com.agenticfx.core.execution.ExecutionStatus;
import com.agenticfx.core.execution.ProposalOrigin;
import com.agenticfx.core.execution.RejectionSource;
import com.agenticfx.core.mandate.MandateRef;
import com.agenticfx.core.money.ExchangeRate;
import com.agenticfx.core.port.Repositories;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.agenticfx.app.persistence.Sql.*;

@Repository
public class JdbcProposals implements Repositories.Proposals {

    private static final String SELECT = """
            SELECT p.*, q.id AS q_id, q.source_currency AS q_source_currency, q.target_currency AS q_target_currency,
                   q.rate AS q_rate, q.source_amount AS q_source_amount, q.fee AS q_fee,
                   q.target_amount AS q_target_amount, q.provider AS q_provider, q.quoted_at AS q_quoted_at,
                   q.expires_at AS q_expires_at
            FROM execution_proposals p JOIN fx_quotes q ON q.id = p.quote_id
            """;
    private static final String OPEN = "('PROPOSED', 'AWAITING_APPROVAL', 'APPROVED', 'EXECUTING')";

    private final JdbcClient jdbc;
    private final JsonCodec json;
    private final RowMapper<ExecutionProposal> mapper;

    public JdbcProposals(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
        RowMapper<com.agenticfx.core.fx.FxQuote> quotes = JdbcQuotes.mapper("q_");
        this.mapper = (rs, i) -> {
            var quote = quotes.mapRow(rs, i);
            var approved = rs.getBigDecimal("approved_rate");
            String rejection = rs.getString("rejection_source");
            return new ExecutionProposal(uuid(rs, "id"), uuid(rs, "goal_id"), uuid(rs, "user_id"),
                    new MandateRef(uuid(rs, "mandate_id"), rs.getInt("mandate_version")), quote,
                    ExecutionStatus.valueOf(rs.getString("status")), ProposalOrigin.valueOf(rs.getString("origin")),
                    json.read(rs.getString("explanation"), Explanation.class), rs.getString("idempotency_key"),
                    approved == null ? null : new ExchangeRate(quote.pair(), approved),
                    rejection == null ? null : RejectionSource.valueOf(rejection), rs.getString("status_reason"),
                    instant(rs, "created_at"), instant(rs, "updated_at"), rs.getLong("version"));
        };
    }

    @Override
    public void insert(ExecutionProposal p) {
        run(() -> jdbc.sql("""
                        INSERT INTO execution_proposals (id, goal_id, user_id, mandate_id, mandate_version, quote_id,
                            status, origin, explanation, idempotency_key, approved_rate, rejection_source,
                            status_reason, created_at, updated_at, version)
                        VALUES (:id, :goalId, :userId, :mandateId, :mandateVersion, :quoteId, :status, :origin,
                            CAST(:explanation AS jsonb), :key, :approvedRate, :rejection, :reason, :createdAt,
                            :updatedAt, 0)""")
                .param("id", p.id()).param("goalId", p.goalId()).param("userId", p.userId())
                .param("mandateId", p.mandateRef().mandateId()).param("mandateVersion", p.mandateRef().version())
                .param("quoteId", p.quote().id()).param("status", p.status().name())
                .param("origin", p.origin().name()).param("explanation", json.write(p.explanation()))
                .param("key", p.idempotencyKey())
                .param("approvedRate", p.approvedRate().map(ExchangeRate::rate).orElse(null))
                .param("rejection", p.rejectionSource().map(Enum::name).orElse(null))
                .param("reason", p.statusReason().orElse(null))
                .param("createdAt", ts(p.createdAt())).param("updatedAt", ts(p.updatedAt()))
                .update());
    }

    @Override
    public void update(ExecutionProposal p) {
        int[] rows = new int[1];
        run(() -> rows[0] = jdbc.sql("""
                        UPDATE execution_proposals SET quote_id = :quoteId, status = :status,
                            approved_rate = :approvedRate, rejection_source = :rejection, status_reason = :reason,
                            updated_at = :updatedAt, version = version + 1
                        WHERE id = :id AND version = :version""")
                .param("quoteId", p.quote().id()).param("status", p.status().name())
                .param("approvedRate", p.approvedRate().map(ExchangeRate::rate).orElse(null))
                .param("rejection", p.rejectionSource().map(Enum::name).orElse(null))
                .param("reason", p.statusReason().map(r -> r.length() > 500 ? r.substring(0, 500) : r).orElse(null))
                .param("updatedAt", ts(p.updatedAt())).param("id", p.id()).param("version", p.version())
                .update());
        requireOneRow(rows[0], "Proposal", p.id());
        p.versionIncremented();
    }

    @Override
    public Optional<ExecutionProposal> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE p.id = :id").param("id", id).query(mapper).optional();
    }

    @Override
    public Optional<ExecutionProposal> findByIdempotencyKey(UUID userId, String key) {
        return jdbc.sql(SELECT + " WHERE p.user_id = :userId AND p.idempotency_key = :key")
                .param("userId", userId).param("key", key).query(mapper).optional();
    }

    @Override
    public Optional<ExecutionProposal> findOpen(UUID goalId) {
        return jdbc.sql(SELECT + " WHERE p.goal_id = :goalId AND p.status IN " + OPEN)
                .param("goalId", goalId).query(mapper).optional();
    }

    @Override
    public List<ExecutionProposal> findByGoal(UUID goalId) {
        return jdbc.sql(SELECT + " WHERE p.goal_id = :goalId ORDER BY p.created_at")
                .param("goalId", goalId).query(mapper).list();
    }

    @Override
    public List<ExecutionProposal> findExecutingSince(Instant before) {
        return jdbc.sql(SELECT + " WHERE p.status = 'EXECUTING' AND p.updated_at <= :before")
                .param("before", ts(before)).query(mapper).list();
    }

    @Override
    public Optional<Instant> lastRejectionAt(UUID goalId) {
        return jdbc.sql("""
                        SELECT max(updated_at) AS at FROM execution_proposals
                        WHERE goal_id = :goalId AND status = 'REJECTED' AND rejection_source IN ('POLICY', 'USER')""")
                .param("goalId", goalId)
                .query((rs, i) -> Optional.ofNullable(instant(rs, "at")))
                .single();
    }
}
