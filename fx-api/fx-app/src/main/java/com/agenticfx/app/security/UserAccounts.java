package com.agenticfx.app.security;

import com.agenticfx.app.persistence.JdbcAccounts;
import com.agenticfx.core.money.Currency;
import com.agenticfx.core.money.Money;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserAccounts {

    public record UserRecord(UUID id, String email, String displayName, String passwordHash, List<String> roles) {
    }

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final JdbcAccounts accounts;
    private final String dummyHash;

    public UserAccounts(JdbcClient jdbc, PasswordEncoder passwords, JdbcAccounts accounts) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.accounts = accounts;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public Optional<UserRecord> findByEmail(String email) {
        return jdbc.sql("SELECT * FROM users WHERE lower(email) = lower(:email)").param("email", email)
                .query((rs, i) -> new UserRecord(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("display_name"), rs.getString("password_hash"), roles(rs.getArray("roles"))))
                .optional();
    }

    public Optional<UserRecord> find(UUID id) {
        return jdbc.sql("SELECT * FROM users WHERE id = :id").param("id", id)
                .query((rs, i) -> new UserRecord(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("display_name"), rs.getString("password_hash"), roles(rs.getArray("roles"))))
                .optional();
    }

    /** Verifies a password in constant time relative to user existence (hashes a dummy when not found). */
    public Optional<UserRecord> authenticate(String email, String password) {
        Optional<UserRecord> user = findByEmail(email);
        String hash = user.map(UserRecord::passwordHash).orElse(dummyHash);
        boolean matches = passwords.matches(password, hash);
        return user.filter(u -> matches);
    }

    /** Creates a customer with a multi-currency account and opening balances (used by the demo seed). */
    @Transactional
    public UUID createCustomer(UUID id, String email, String displayName, String rawPassword,
                               Map<Currency, Money> openingBalances) {
        jdbc.sql("""
                        INSERT INTO users (id, email, display_name, password_hash, roles)
                        VALUES (:id, :email, :name, :hash, ARRAY['CUSTOMER'])""")
                .param("id", id).param("email", email).param("name", displayName)
                .param("hash", passwords.encode(rawPassword))
                .update();
        accounts.open(id, openingBalances);
        return id;
    }

    private static List<String> roles(Array array) throws java.sql.SQLException {
        return array == null ? List.of() : Arrays.stream((Object[]) array.getArray()).map(Object::toString).toList();
    }
}
