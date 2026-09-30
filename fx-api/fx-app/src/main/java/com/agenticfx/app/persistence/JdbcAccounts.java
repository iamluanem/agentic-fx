package com.agenticfx.app.persistence;

import com.agenticfx.core.account.Balance;
import com.agenticfx.core.account.CurrencyAccount;
import com.agenticfx.core.money.Currency;
import com.agenticfx.core.money.Money;
import com.agenticfx.core.port.Repositories;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.agenticfx.app.persistence.Sql.*;

@Repository
public class JdbcAccounts implements Repositories.Accounts {

    private final JdbcClient jdbc;

    public JdbcAccounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CurrencyAccount> findByUser(UUID userId) {
        record Header(UUID id, long version) {
        }
        Optional<Header> header = jdbc.sql("SELECT id, version FROM currency_accounts WHERE user_id = :userId")
                .param("userId", userId)
                .query((rs, i) -> new Header(uuid(rs, "id"), rs.getLong("version")))
                .optional();
        return header.map(h -> {
            Map<Currency, Balance> balances = new EnumMap<>(Currency.class);
            jdbc.sql("SELECT currency, available FROM balances WHERE account_id = :id").param("id", h.id())
                    .query((rs, i) -> {
                        Currency c = currency(rs, "currency");
                        return new Balance(c, Money.of(rs.getBigDecimal("available"), c));
                    })
                    .list()
                    .forEach(b -> balances.put(b.currency(), b));
            return new CurrencyAccount(h.id(), userId, balances, h.version());
        });
    }

    /** Bumps the account version (optimistic lock) and upserts every balance in the same transaction. */
    @Override
    public void update(CurrencyAccount account) {
        int rows = jdbc.sql("""
                        UPDATE currency_accounts SET version = version + 1, updated_at = :now
                        WHERE id = :id AND version = :version""")
                .param("now", ts(Instant.now())).param("id", account.id()).param("version", account.version())
                .update();
        requireOneRow(rows, "Account", account.id());
        account.balances().values().forEach(b -> jdbc.sql("""
                        INSERT INTO balances (account_id, currency, available, updated_at)
                        VALUES (:id, :currency, :available, now())
                        ON CONFLICT (account_id, currency)
                        DO UPDATE SET available = EXCLUDED.available, updated_at = now()""")
                .param("id", account.id()).param("currency", b.currency().name())
                .param("available", b.available().amount())
                .update());
        account.versionIncremented();
    }

    /** Creates an account with opening balances (used by the demo seed and sign-up). */
    public UUID open(UUID userId, Map<Currency, Money> opening) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO currency_accounts (id, user_id) VALUES (:id, :userId)")
                .param("id", id).param("userId", userId).update();
        opening.forEach((currency, money) -> jdbc.sql(
                        "INSERT INTO balances (account_id, currency, available) VALUES (:id, :currency, :available)")
                .param("id", id).param("currency", currency.name()).param("available", money.amount())
                .update());
        return id;
    }
}
