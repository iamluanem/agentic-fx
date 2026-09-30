package com.agenticfx.app.persistence;

import com.agenticfx.core.money.Currency;
import com.agenticfx.core.money.Money;
import com.agenticfx.core.shared.ConflictException;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** JDBC helpers shared by the repositories. */
final class Sql {

    private Sql() {
    }

    static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    static Currency currency(ResultSet rs, String column) throws SQLException {
        String code = rs.getString(column);
        return code == null ? null : Currency.of(code);
    }

    static Money money(ResultSet rs, String amountColumn, Currency currency) throws SQLException {
        BigDecimal amount = rs.getBigDecimal(amountColumn);
        return amount == null ? null : Money.of(amount, currency);
    }

    static BigDecimal amount(Money money) {
        return money == null ? null : money.amount();
    }

    static String code(Money money) {
        return money == null ? null : money.currency().name();
    }

    /** Optimistic locking: an UPDATE guarded by the expected version must touch exactly one row. */
    static void requireOneRow(int rows, String what, Object id) {
        if (rows != 1) {
            throw new ConflictException("CONCURRENT_MODIFICATION",
                    what + " " + id + " was modified concurrently; reload and retry");
        }
    }

    /** Maps unique-constraint violations to domain conflicts with stable codes. */
    static RuntimeException translate(DataIntegrityViolationException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains("ux_execution_proposals_one_open_per_goal")) {
            return new ConflictException("PROPOSAL_ALREADY_OPEN", "Another proposal is already open for this goal");
        }
        if (message.contains("ux_agent_mandates_one_active_per_goal")) {
            return new ConflictException("MANDATE_ALREADY_ACTIVE", "Another mandate version is already active");
        }
        if (message.contains("ux_approval_requests_one_pending")) {
            return new ConflictException("APPROVAL_ALREADY_PENDING", "An approval is already pending");
        }
        if (message.contains("fx_conversions_proposal_id_key")) {
            return new ConflictException("ALREADY_EXECUTED", "This proposal was already executed");
        }
        if (message.contains("idempotency_key")) {
            return new ConflictException("IDEMPOTENCY_KEY_REUSED", "Idempotency key already used");
        }
        return new ConflictException("CONSTRAINT_VIOLATION", "The request conflicts with stored data");
    }

    static void run(Runnable statement) {
        try {
            statement.run();
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }
}
