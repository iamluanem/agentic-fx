package com.agenticfx.app.persistence;

import com.agenticfx.core.agent.AgentDecision;
import com.agenticfx.core.agent.AgentDecisionType;
import com.agenticfx.core.agent.Explanation;
import com.agenticfx.core.execution.ProposalOrigin;
import com.agenticfx.core.port.Repositories;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

import static com.agenticfx.app.persistence.Sql.*;

@Repository
public class JdbcAgentDecisions implements Repositories.AgentDecisions {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    public JdbcAgentDecisions(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void insert(AgentDecision d) {
        run(() -> jdbc.sql("""
                        INSERT INTO agent_decisions (id, goal_id, user_id, type, source, summary, explanation,
                            proposal_id, decided_at)
                        VALUES (:id, :goalId, :userId, :type, :source, :summary, CAST(:explanation AS jsonb),
                            :proposalId, :decidedAt)""")
                .param("id", d.id()).param("goalId", d.goalId()).param("userId", d.userId())
                .param("type", d.type().name()).param("source", d.source().name())
                .param("summary", d.summary().length() <= 1000 ? d.summary() : d.summary().substring(0, 1000))
                .param("explanation", json.write(d.explanation())).param("proposalId", d.proposalId())
                .param("decidedAt", ts(d.decidedAt()))
                .update());
    }

    @Override
    public List<AgentDecision> findByGoal(UUID goalId, int limit) {
        return jdbc.sql("SELECT * FROM agent_decisions WHERE goal_id = :id ORDER BY decided_at DESC, id LIMIT :limit")
                .param("id", goalId).param("limit", limit)
                .query((rs, i) -> new AgentDecision(uuid(rs, "id"), uuid(rs, "goal_id"), uuid(rs, "user_id"),
                        AgentDecisionType.valueOf(rs.getString("type")), ProposalOrigin.valueOf(rs.getString("source")),
                        rs.getString("summary"), json.read(rs.getString("explanation"), Explanation.class),
                        uuid(rs, "proposal_id"), instant(rs, "decided_at")))
                .list();
    }
}
