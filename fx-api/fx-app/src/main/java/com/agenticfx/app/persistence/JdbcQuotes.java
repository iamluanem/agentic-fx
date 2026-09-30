package com.agenticfx.app.persistence;

import com.agenticfx.core.fx.FxQuote;
import com.agenticfx.core.money.Currency;
import com.agenticfx.core.money.CurrencyPair;
import com.agenticfx.core.money.ExchangeRate;
import com.agenticfx.core.port.Repositories;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

import static com.agenticfx.app.persistence.Sql.*;

@Repository
public class JdbcQuotes implements Repositories.Quotes {

    /** Maps columns prefixed with {@code q_} (see the proposal query) or unprefixed ones. */
    static RowMapper<FxQuote> mapper(String prefix) {
        return (rs, i) -> {
            Currency source = currency(rs, prefix + "source_currency");
            Currency target = currency(rs, prefix + "target_currency");
            CurrencyPair pair = CurrencyPair.of(source, target);
            return new FxQuote(uuid(rs, prefix + "id"), pair, new ExchangeRate(pair, rs.getBigDecimal(prefix + "rate")),
                    money(rs, prefix + "source_amount", source), money(rs, prefix + "fee", source),
                    money(rs, prefix + "target_amount", target), rs.getString(prefix + "provider"),
                    instant(rs, prefix + "quoted_at"), instant(rs, prefix + "expires_at"));
        };
    }

    private final JdbcClient jdbc;

    public JdbcQuotes(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(FxQuote q, UUID userId) {
        run(() -> jdbc.sql("""
                        INSERT INTO fx_quotes (id, user_id, source_currency, target_currency, rate, source_amount, fee,
                            target_amount, provider, quoted_at, expires_at)
                        VALUES (:id, :userId, :source, :target, :rate, :sourceAmount, :fee, :targetAmount, :provider,
                            :quotedAt, :expiresAt)""")
                .param("id", q.id()).param("userId", userId).param("source", q.pair().source().name())
                .param("target", q.pair().target().name()).param("rate", q.rate().rate())
                .param("sourceAmount", q.sourceAmount().amount()).param("fee", q.fee().amount())
                .param("targetAmount", q.targetAmount().amount()).param("provider", q.provider())
                .param("quotedAt", ts(q.quotedAt())).param("expiresAt", ts(q.expiresAt()))
                .update());
    }
}
