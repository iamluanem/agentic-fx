package com.agenticfx.core.fx;

import com.agenticfx.core.market.MarketSimulator;
import com.agenticfx.core.market.RateBook;
import com.agenticfx.core.money.Currency;
import com.agenticfx.core.money.CurrencyPair;
import com.agenticfx.core.money.Money;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simulated liquidity provider. Quotes are firm (settle exactly as quoted), fees are a percentage of the
 * source amount, and execution is idempotent on the request's idempotency key.
 */
public final class MockFxProvider implements FxProvider {

    public static final String NAME = "mock";

    private final RateBook rates;
    private final BigDecimal feeRate;
    private final Duration quoteTtl;
    private final Clock clock;
    private final Map<String, ConversionResult> executed = new ConcurrentHashMap<>();
    private final AtomicBoolean failNext = new AtomicBoolean(false);

    public MockFxProvider(RateBook rates, BigDecimal feeRate, Duration quoteTtl, Clock clock) {
        this.rates = rates;
        this.feeRate = feeRate;
        this.quoteTtl = quoteTtl;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public FxQuote getQuote(Currency source, Currency target, BigDecimal amount) {
        CurrencyPair pair = CurrencyPair.of(source, target);
        var observation = rates.observe(pair).orElseThrow(() -> MarketSimulator.unsupported(pair));
        Money sourceAmount = Money.of(amount, source);
        if (!sourceAmount.isPositive()) {
            throw new IllegalArgumentException("Quote amount must be positive");
        }
        Money fee = sourceAmount.times(feeRate);
        Instant now = clock.instant();
        return FxQuote.price(pair, observation.rate(), sourceAmount, fee, NAME, now, now.plus(quoteTtl));
    }

    @Override
    public ConversionResult execute(ConversionRequest request) {
        return executed.computeIfAbsent(request.idempotencyKey(), key -> settle(request));
    }

    /** Makes the next execution fail — lets tests and demos exercise the failure path. */
    public void failNextExecution() {
        failNext.set(true);
    }

    private ConversionResult settle(ConversionRequest request) {
        FxQuote quote = request.quote();
        Instant now = clock.instant();
        String reference = "mock-" + UUID.randomUUID();
        if (failNext.getAndSet(false)) {
            return ConversionResult.failed(reference, "PROVIDER_UNAVAILABLE");
        }
        if (quote.isExpiredAt(now)) {
            return ConversionResult.failed(reference, "QUOTE_EXPIRED");
        }
        return ConversionResult.succeeded(reference, quote.sourceAmount(), quote.fee(), quote.targetAmount(), now);
    }
}
