package com.agenticfx.app.persistence;

import com.agenticfx.core.fx.FxConversion;
import com.agenticfx.core.mandate.MandateRef;
import com.agenticfx.core.money.Currency;
import com.agenticfx.core.money.CurrencyPair;
import com.agenticfx.core.money.ExchangeRate;
import com.agenticfx.core.port.Repositories;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.agenticfx.app.persistence.Sql.*;

@Repository
public class JdbcConversions implements Repositories.Conversions {

    private static final RowMapper<FxConversion> MAPPER = (rs, i) -> {
        Currency source = currency(rs, "source_currency");
        Currency target = currency(rs, "target_currency");
        CurrencyPair pair = CurrencyPair.of(source, target);
        return new FxConversion(uuid(rs, "id"), uuid(rs, "proposal_id"), uuid(rs, "goal_id"), uuid(rs, "user_id"),
                new MandateRef(uuid(rs, "mandate_id"), rs.getInt("mandate_version")), uuid(rs, "quote_id"),
                rs.getString("provider"), rs.getString("provider_reference"), pair,
                new ExchangeRate(pair, rs.getBigDecimal("rate")), money(rs, "source_amount", source),
                money(rs, "fee", source), money(rs, "target_amount", target), instant(rs, "executed_at"));
    };

    private final JdbcClient jdbc;

    public JdbcConversions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(FxConversion c) {
        run(() -> jdbc.sql("""
                        INSERT INTO fx_conversions (id, proposal_id, goal_id, user_id, mandate_id, mandate_version,
                            quote_id, provider, provider_reference, source_currency, target_currency, rate,
                            source_amount, fee, target_amount, executed_at)
                        VALUES (:id, :proposalId, :goalId, :userId, :mandateId, :mandateVersion, :quoteId, :provider,
                            :reference, :source, :target, :rate, :sourceAmount, :fee, :targetAmount, :executedAt)""")
                .param("id", c.id()).param("proposalId", c.proposalId()).param("goalId", c.goalId())
                .param("userId", c.userId()).param("mandateId", c.mandateRef().mandateId())
                .param("mandateVersion", c.mandateRef().version()).param("quoteId", c.quoteId())
                .param("provider", c.provider()).param("reference", c.providerReference())
                .param("source", c.pair().source().name()).param("target", c.pair().target().name())
                .param("rate", c.rate().rate()).param("sourceAmount", c.sourceAmount().amount())
                .param("fee", c.fee().amount()).param("targetAmount", c.targetAmount().amount())
                .param("executedAt", ts(c.executedAt()))
                .update());
    }

    @Override
    public Optional<FxConversion> findByProposal(UUID proposalId) {
        return jdbc.sql("SELECT * FROM fx_conversions WHERE proposal_id = :id").param("id", proposalId)
                .query(MAPPER).optional();
    }

    @Override
    public List<FxConversion> findByGoal(UUID goalId) {
        return jdbc.sql("SELECT * FROM fx_conversions WHERE goal_id = :id ORDER BY executed_at DESC")
                .param("id", goalId).query(MAPPER).list();
    }

    @Override
    public List<FxConversion> findByUser(UUID userId) {
        return jdbc.sql("SELECT * FROM fx_conversions WHERE user_id = :id ORDER BY executed_at DESC LIMIT 200")
                .param("id", userId).query(MAPPER).list();
    }
}
