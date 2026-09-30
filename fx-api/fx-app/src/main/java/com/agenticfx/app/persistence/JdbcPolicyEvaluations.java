package com.agenticfx.app.persistence;

import com.agenticfx.core.mandate.MandateRef;
import com.agenticfx.core.policy.Decision;
import com.agenticfx.core.policy.Finding;
import com.agenticfx.core.policy.PolicyDecision;
import com.agenticfx.core.policy.ReasonCode;
import com.agenticfx.core.port.Repositories;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.agenticfx.app.persistence.Sql.*;

@Repository
public class JdbcPolicyEvaluations implements Repositories.PolicyEvaluations {

    private static final TypeReference<List<Finding>> FINDINGS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonCodec json;

    public JdbcPolicyEvaluations(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void insert(PolicyDecision d, UUID quoteId) {
        run(() -> jdbc.sql("""
                        INSERT INTO policy_evaluations (id, proposal_id, mandate_id, mandate_version, decision,
                            reason_codes, explanation, findings, policy_version, quote_id, evaluated_at)
                        VALUES (:id, :proposalId, :mandateId, :mandateVersion, :decision, :codes, :explanation,
                            CAST(:findings AS jsonb), :policyVersion, :quoteId, :evaluatedAt)""")
                .param("id", UUID.randomUUID()).param("proposalId", d.proposalId())
                .param("mandateId", d.mandateRef().mandateId()).param("mandateVersion", d.mandateRef().version())
                .param("decision", d.decision().name())
                .param("codes", d.reasonCodes().stream().map(Enum::name).toArray(String[]::new))
                .param("explanation", truncate(d.explanation())).param("findings", json.write(d.findings()))
                .param("policyVersion", d.policyVersion()).param("quoteId", quoteId)
                .param("evaluatedAt", ts(d.evaluatedAt()))
                .update());
    }

    @Override
    public List<PolicyDecision> findByProposal(UUID proposalId) {
        return jdbc.sql("SELECT * FROM policy_evaluations WHERE proposal_id = :id ORDER BY evaluated_at")
                .param("id", proposalId)
                .query((rs, i) -> new PolicyDecision(Decision.valueOf(rs.getString("decision")),
                        codes(rs.getArray("reason_codes")), rs.getString("explanation"),
                        json.read(rs.getString("findings"), FINDINGS), rs.getString("policy_version"),
                        uuid(rs, "proposal_id"), new MandateRef(uuid(rs, "mandate_id"), rs.getInt("mandate_version")),
                        instant(rs, "evaluated_at")))
                .list();
    }

    static List<ReasonCode> codes(Array array) throws java.sql.SQLException {
        if (array == null) {
            return List.of();
        }
        return Arrays.stream((Object[]) array.getArray()).map(o -> ReasonCode.valueOf(o.toString())).toList();
    }

    private static String truncate(String s) {
        return s.length() <= 2000 ? s : s.substring(0, 1999) + "…";
    }
}
