package com.agenticfx.core.account;

import com.agenticfx.core.money.Currency;
import com.agenticfx.core.money.Money;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Multi-currency wallet. Mutations are guarded by the persistence layer's optimistic lock ({@code version}). */
public class CurrencyAccount {

    private final UUID id;
    private final UUID userId;
    private final EnumMap<Currency, Balance> balances;
    private long version;

    public CurrencyAccount(UUID id, UUID userId, Map<Currency, Balance> balances, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.balances = new EnumMap<>(Currency.class);
        this.balances.putAll(balances);
        this.version = version;
    }

    public static CurrencyAccount open(UUID userId) {
        return new CurrencyAccount(UUID.randomUUID(), userId, Map.of(), 0);
    }

    public Money availableIn(Currency currency) {
        return balances.getOrDefault(currency, Balance.empty(currency)).available();
    }

    public void credit(Money amount) {
        requirePositive(amount);
        balances.put(amount.currency(), new Balance(amount.currency(), availableIn(amount.currency()).plus(amount)));
    }

    public void debit(Money amount) {
        requirePositive(amount);
        Money available = availableIn(amount.currency());
        if (available.isLessThan(amount)) {
            throw new InsufficientFundsException(amount, available);
        }
        balances.put(amount.currency(), new Balance(amount.currency(), available.minus(amount)));
    }

    /** Immutable view used by the Policy Engine. */
    public Map<Currency, Money> snapshot() {
        EnumMap<Currency, Money> view = new EnumMap<>(Currency.class);
        balances.forEach((currency, balance) -> view.put(currency, balance.available()));
        return Collections.unmodifiableMap(view);
    }

    public Map<Currency, Balance> balances() {
        return Collections.unmodifiableMap(balances);
    }

    private static void requirePositive(Money amount) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Amount must be positive: " + amount);
        }
    }

    /** Called by the persistence adapter after a successful optimistic-locked update. */
    public void versionIncremented() {
        version++;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public long version() {
        return version;
    }
}
