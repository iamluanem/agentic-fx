package com.agenticfx.app.persistence;

import com.agenticfx.core.approval.ApprovalRequest;
import com.agenticfx.core.approval.ApprovalStatus;
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
public class JdbcApprovals implements Repositories.Approvals {

    private static final RowMapper<ApprovalRequest> MAPPER = (rs, i) -> new ApprovalRequest(uuid(rs, "id"),
            uuid(rs, "proposal_id"), uuid(rs, "goal_id"), uuid(rs, "user_id"),
            ApprovalStatus.valueOf(rs.getString("status")), instant(rs, "requested_at"), instant(rs, "expires_at"),
            instant(rs, "decided_at"), uuid(rs, "decided_by"), rs.getString("note"), rs.getLong("version"));

    private final JdbcClient jdbc;

    public JdbcApprovals(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(ApprovalRequest a) {
        run(() -> jdbc.sql("""
                        INSERT INTO approval_requests (id, proposal_id, goal_id, user_id, status, requested_at,
                            expires_at, decided_at, decided_by, note, version)
                        VALUES (:id, :proposalId, :goalId, :userId, :status, :requestedAt, :expiresAt, :decidedAt,
                            :decidedBy, :note, 0)""")
                .param("id", a.id()).param("proposalId", a.proposalId()).param("goalId", a.goalId())
                .param("userId", a.userId()).param("status", a.status().name())
                .param("requestedAt", ts(a.requestedAt())).param("expiresAt", ts(a.expiresAt()))
                .param("decidedAt", ts(a.decidedAt())).param("decidedBy", a.decidedBy()).param("note", a.note())
                .update());
    }

    @Override
    public void update(ApprovalRequest a) {
        int rows = jdbc.sql("""
                        UPDATE approval_requests SET status = :status, decided_at = :decidedAt,
                            decided_by = :decidedBy, note = :note, version = version + 1
                        WHERE id = :id AND version = :version""")
                .param("status", a.status().name()).param("decidedAt", ts(a.decidedAt()))
                .param("decidedBy", a.decidedBy()).param("note", a.note()).param("id", a.id())
                .param("version", a.version())
                .update();
        requireOneRow(rows, "Approval", a.id());
        a.versionIncremented();
    }

    @Override
    public Optional<ApprovalRequest> find(UUID id) {
        return jdbc.sql("SELECT * FROM approval_requests WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    @Override
    public Optional<ApprovalRequest> findPendingByProposal(UUID proposalId) {
        return jdbc.sql("SELECT * FROM approval_requests WHERE proposal_id = :id AND status = 'PENDING'")
                .param("id", proposalId).query(MAPPER).optional();
    }

    @Override
    public List<ApprovalRequest> findByUser(UUID userId, boolean pendingOnly) {
        return jdbc.sql("SELECT * FROM approval_requests WHERE user_id = :userId"
                        + (pendingOnly ? " AND status = 'PENDING'" : "") + " ORDER BY requested_at DESC LIMIT 100")
                .param("userId", userId).query(MAPPER).list();
    }

    @Override
    public List<ApprovalRequest> findPendingExpiredAt(Instant now) {
        return jdbc.sql("SELECT * FROM approval_requests WHERE status = 'PENDING' AND expires_at <= :now")
                .param("now", ts(now)).query(MAPPER).list();
    }
}
