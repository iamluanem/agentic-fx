package com.agenticfx.app.persistence;

import com.agenticfx.core.audit.AuditEvent;
import com.agenticfx.core.audit.AuditEventType;
import com.agenticfx.core.mandate.MandateRef;
import com.agenticfx.core.money.Currency;
import com.agenticfx.core.policy.Decision;
import com.agenticfx.core.port.Repositories;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.agenticfx.app.persistence.Sql.*;

/** Append-only. The table has triggers rejecting UPDATE, DELETE and TRUNCATE. */
@Repository
public class JdbcAuditLog implements Repositories.AuditLog {

    private static final TypeReference<Map<String, String>> DETAILS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonCodec json;
    private final RowMapper<AuditEvent> mapper;

    public JdbcAuditLog(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, i) -> {
            UUID mandateId = uuid(rs, "mandate_id");
            String policy = rs.getString("policy_decision");
            Currency requestedCurrency = currency(rs, "requested_currency");
            Currency executedCurrency = currency(rs, "executed_currency");
            return new AuditEvent(uuid(rs, "id"), instant(rs, "occurred_at"),
                    AuditEventType.valueOf(rs.getString("type")), rs.getString("actor"), uuid(rs, "user_id"),
                    uuid(rs, "goal_id"), mandateId == null ? null : new MandateRef(mandateId, rs.getInt("mandate_version")),
                    uuid(rs, "proposal_id"), rs.getString("agent_decision"),
                    policy == null ? null : Decision.valueOf(policy),
                    JdbcPolicyEvaluations.codes(rs.getArray("reason_codes")), uuid(rs, "quote_id"),
                    rs.getString("quote_rate"), rs.getString("provider"),
                    requestedCurrency == null ? null : money(rs, "requested_amount", requestedCurrency),
                    executedCurrency == null ? null : money(rs, "executed_amount", executedCurrency),
                    rs.getString("summary"), rs.getString("correlation_id"),
                    json.read(rs.getString("details"), DETAILS));
        };
    }

    @Override
    public void append(AuditEvent e) {
        run(() -> jdbc.sql("""
                        INSERT INTO audit_events (id, occurred_at, type, actor, user_id, goal_id, mandate_id,
                            mandate_version, proposal_id, agent_decision, policy_decision, reason_codes, quote_id,
                            quote_rate, provider, requested_amount, requested_currency, executed_amount,
                            executed_currency, summary, correlation_id, details)
                        VALUES (:id, :at, :type, :actor, :userId, :goalId, :mandateId, :mandateVersion, :proposalId,
                            :agentDecision, :policy, :codes, :quoteId, :quoteRate, :provider, :requested,
                            :requestedCurrency, :executed, :executedCurrency, :summary, :correlationId,
                            CAST(:details AS jsonb))""")
                .param("id", e.id()).param("at", ts(e.timestamp())).param("type", e.type().name())
                .param("actor", e.actor()).param("userId", e.userId()).param("goalId", e.goalId())
                .param("mandateId", e.mandateRef() == null ? null : e.mandateRef().mandateId())
                .param("mandateVersion", e.mandateRef() == null ? null : e.mandateRef().version())
                .param("proposalId", e.proposalId()).param("agentDecision", clip(e.agentDecision(), 1000))
                .param("policy", e.policyDecision() == null ? null : e.policyDecision().name())
                .param("codes", e.reasonCodes().stream().map(Enum::name).toArray(String[]::new))
                .param("quoteId", e.quoteId()).param("quoteRate", e.quoteRate()).param("provider", e.provider())
                .param("requested", amount(e.requestedAmount())).param("requestedCurrency", code(e.requestedAmount()))
                .param("executed", amount(e.executedAmount())).param("executedCurrency", code(e.executedAmount()))
                .param("summary", clip(e.summary(), 2000)).param("correlationId", e.correlationId())
                .param("details", json.write(e.details()))
                .update());
    }

    @Override
    public List<AuditEvent> findByGoal(UUID goalId) {
        return jdbc.sql("SELECT * FROM audit_events WHERE goal_id = :id ORDER BY seq").param("id", goalId)
                .query(mapper).list();
    }

    @Override
    public List<AuditEvent> findByUser(UUID userId, int limit) {
        return jdbc.sql("SELECT * FROM audit_events WHERE user_id = :id ORDER BY seq DESC LIMIT :limit")
                .param("id", userId).param("limit", limit).query(mapper).list();
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
