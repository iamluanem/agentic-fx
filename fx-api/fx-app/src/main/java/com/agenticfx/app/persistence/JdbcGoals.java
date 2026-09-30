package com.agenticfx.app.persistence;

import com.agenticfx.core.goal.FxGoal;
import com.agenticfx.core.goal.FxStrategy;
import com.agenticfx.core.goal.GoalStatus;
import com.agenticfx.core.goal.StrategyType;
import com.agenticfx.core.money.Currency;
import com.agenticfx.core.money.CurrencyPair;
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
public class JdbcGoals implements Repositories.Goals {

    private static final RowMapper<FxGoal> MAPPER = (rs, i) -> {
        Currency source = currency(rs, "source_currency");
        Currency target = currency(rs, "target_currency");
        return new FxGoal(uuid(rs, "id"), uuid(rs, "user_id"), rs.getString("name"),
                CurrencyPair.of(source, target), money(rs, "target_amount", target),
                rs.getObject("deadline", LocalDate.class), money(rs, "max_budget", source),
                new FxStrategy(StrategyType.valueOf(rs.getString("strategy_type")), rs.getBigDecimal("target_rate"),
                        rs.getInt("planned_tranches"), rs.getInt("min_improvement_bps")),
                GoalStatus.valueOf(rs.getString("status")), money(rs, "acquired_amount", target),
                money(rs, "spent_amount", source), instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getLong("version"));
    };

    private final JdbcClient jdbc;

    public JdbcGoals(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(FxGoal g) {
        run(() -> jdbc.sql("""
                        INSERT INTO fx_goals (id, user_id, name, source_currency, target_currency, target_amount,
                            deadline, max_budget, strategy_type, target_rate, planned_tranches, min_improvement_bps,
                            status, acquired_amount, spent_amount, created_at, updated_at, version)
                        VALUES (:id, :userId, :name, :source, :target, :targetAmount, :deadline, :maxBudget,
                            :strategyType, :targetRate, :tranches, :minBps, :status, :acquired, :spent,
                            :createdAt, :updatedAt, :version)""")
                .param("id", g.id()).param("userId", g.userId()).param("name", g.name())
                .param("source", g.pair().source().name()).param("target", g.pair().target().name())
                .param("targetAmount", g.targetAmount().amount()).param("deadline", g.deadline())
                .param("maxBudget", g.maxBudget().amount()).param("strategyType", g.strategy().type().name())
                .param("targetRate", g.strategy().targetRate()).param("tranches", g.strategy().plannedTranches())
                .param("minBps", g.strategy().minImprovementBps()).param("status", g.status().name())
                .param("acquired", g.acquired().amount()).param("spent", g.spent().amount())
                .param("createdAt", ts(g.createdAt())).param("updatedAt", ts(g.updatedAt()))
                .param("version", g.version())
                .update());
    }

    @Override
    public void update(FxGoal g) {
        int rows = jdbc.sql("""
                        UPDATE fx_goals SET status = :status, acquired_amount = :acquired, spent_amount = :spent,
                            updated_at = :updatedAt, version = version + 1
                        WHERE id = :id AND version = :version""")
                .param("status", g.status().name()).param("acquired", g.acquired().amount())
                .param("spent", g.spent().amount()).param("updatedAt", ts(g.updatedAt()))
                .param("id", g.id()).param("version", g.version())
                .update();
        requireOneRow(rows, "Goal", g.id());
        g.versionIncremented();
    }

    @Override
    public Optional<FxGoal> find(UUID id) {
        return jdbc.sql("SELECT * FROM fx_goals WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    @Override
    public List<FxGoal> findByUser(UUID userId) {
        return jdbc.sql("SELECT * FROM fx_goals WHERE user_id = :userId ORDER BY created_at DESC")
                .param("userId", userId).query(MAPPER).list();
    }

    @Override
    public List<FxGoal> findActiveByPair(CurrencyPair pair) {
        return jdbc.sql("""
                        SELECT * FROM fx_goals
                        WHERE status = 'ACTIVE' AND source_currency = :source AND target_currency = :target""")
                .param("source", pair.source().name()).param("target", pair.target().name())
                .query(MAPPER).list();
    }

    @Override
    public List<FxGoal> findUnfinished() {
        return jdbc.sql("SELECT * FROM fx_goals WHERE status IN ('ACTIVE', 'PAUSED')").query(MAPPER).list();
    }
}
