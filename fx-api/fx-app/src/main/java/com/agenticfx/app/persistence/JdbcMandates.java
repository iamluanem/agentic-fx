package com.agenticfx.app.persistence;

import com.agenticfx.core.mandate.AgentMandate;
import com.agenticfx.core.mandate.ExecutionMode;
import com.agenticfx.core.mandate.MandatePermissions;
import com.agenticfx.core.mandate.MandateRef;
import com.agenticfx.core.mandate.MandateStatus;
import com.agenticfx.core.money.Currency;
import com.agenticfx.core.port.Repositories;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.agenticfx.app.persistence.Sql.*;

@Repository
public class JdbcMandates implements Repositories.Mandates {

    private static final RowMapper<AgentMandate> MAPPER = (rs, i) -> {
        Currency source = currency(rs, "source_currency");
        var permissions = new MandatePermissions(source, currency(rs, "target_currency"),
                money(rs, "max_total_spend", source), money(rs, "max_transaction_amount", source),
                money(rs, "approval_threshold", source), rs.getBoolean("allow_partial"));
        return new AgentMandate(uuid(rs, "id"), rs.getInt("version"), uuid(rs, "goal_id"), uuid(rs, "user_id"),
                permissions, ExecutionMode.valueOf(rs.getString("execution_mode")),
                rs.getObject("valid_until", LocalDate.class), MandateStatus.valueOf(rs.getString("status")),
                instant(rs, "created_at"), instant(rs, "activated_at"), instant(rs, "ended_at"),
                rs.getLong("row_version"));
    };

    private final JdbcClient jdbc;

    public JdbcMandates(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(AgentMandate m) {
        var p = m.permissions();
        run(() -> jdbc.sql("""
                        INSERT INTO agent_mandates (id, version, goal_id, user_id, source_currency, target_currency,
                            max_total_spend, max_transaction_amount, approval_threshold, allow_partial, execution_mode,
                            valid_until, status, created_at, activated_at, ended_at, row_version)
                        VALUES (:id, :version, :goalId, :userId, :source, :target, :maxTotal, :maxTx, :threshold,
                            :partial, :mode, :validUntil, :status, :createdAt, :activatedAt, :endedAt, 0)""")
                .param("id", m.id()).param("version", m.version()).param("goalId", m.goalId())
                .param("userId", m.userId()).param("source", p.sourceCurrency().name())
                .param("target", p.targetCurrency().name()).param("maxTotal", p.maxTotalSpend().amount())
                .param("maxTx", p.maxTransactionAmount().amount()).param("threshold", amount(p.approvalThreshold()))
                .param("partial", p.allowPartialExecution()).param("mode", m.executionMode().name())
                .param("validUntil", m.validUntil()).param("status", m.status().name())
                .param("createdAt", ts(m.createdAt())).param("activatedAt", ts(m.activatedAt()))
                .param("endedAt", ts(m.endedAt()))
                .update());
    }

    /** Only lifecycle columns change; a database trigger rejects any change to the terms. */
    @Override
    public void update(AgentMandate m) {
        int[] rows = new int[1];
        run(() -> rows[0] = jdbc.sql("""
                        UPDATE agent_mandates SET status = :status, activated_at = :activatedAt, ended_at = :endedAt,
                            row_version = row_version + 1
                        WHERE id = :id AND version = :version AND row_version = :rowVersion""")
                .param("status", m.status().name()).param("activatedAt", ts(m.activatedAt()))
                .param("endedAt", ts(m.endedAt())).param("id", m.id()).param("version", m.version())
                .param("rowVersion", m.rowVersion())
                .update());
        requireOneRow(rows[0], "Mandate", m.ref());
        m.versionIncremented();
    }

    @Override
    public Optional<AgentMandate> find(MandateRef ref) {
        return jdbc.sql("SELECT * FROM agent_mandates WHERE id = :id AND version = :version")
                .param("id", ref.mandateId()).param("version", ref.version()).query(MAPPER).optional();
    }

    @Override
    public Optional<AgentMandate> findActive(UUID goalId) {
        return jdbc.sql("SELECT * FROM agent_mandates WHERE goal_id = :goalId AND status = 'ACTIVE'")
                .param("goalId", goalId).query(MAPPER).optional();
    }

    @Override
    public Optional<AgentMandate> findLatest(UUID goalId) {
        return jdbc.sql("SELECT * FROM agent_mandates WHERE goal_id = :goalId ORDER BY version DESC LIMIT 1")
                .param("goalId", goalId).query(MAPPER).optional();
    }

    @Override
    public List<AgentMandate> findAll(UUID goalId) {
        return jdbc.sql("SELECT * FROM agent_mandates WHERE goal_id = :goalId ORDER BY version")
                .param("goalId", goalId).query(MAPPER).list();
    }
}
