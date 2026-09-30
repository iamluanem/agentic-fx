package com.agenticfx.core.market;

import com.agenticfx.core.money.CurrencyPair;
import com.agenticfx.core.money.ExchangeRate;
import com.agenticfx.core.shared.DomainException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Simulated FX market. Each tick applies a small mean-reverting random walk around an anchor; setting a rate
 * manually moves both the current rate and the anchor, so the market "stays" at the new level (useful in demos).
 * The reference rate is the average of observations inside the look-back window, excluding the current one.
 */
public final class MarketSimulator implements RateBook {

    public static Map<CurrencyPair, BigDecimal> defaultRates() {
        Map<CurrencyPair, BigDecimal> rates = new LinkedHashMap<>();
        rates.put(CurrencyPair.parse("BRL_EUR"), new BigDecimal("0.1650"));
        rates.put(CurrencyPair.parse("BRL_USD"), new BigDecimal("0.1800"));
        rates.put(CurrencyPair.parse("USD_EUR"), new BigDecimal("0.9150"));
        rates.put(CurrencyPair.parse("GBP_EUR"), new BigDecimal("1.1850"));
        return rates;
    }

    private final Map<CurrencyPair, PairState> pairs = new LinkedHashMap<>();
    private final Clock clock;
    private final Random random;
    private final Duration referenceWindow;
    private final double volatility;
    private final double meanReversion;
    private long sequence;

    /**
     * @param volatilityBps  standard deviation of each tick, in basis points
     * @param meanReversion  fraction of the distance to the anchor recovered per tick (0..1)
     */
    public MarketSimulator(Map<CurrencyPair, BigDecimal> initialRates, Clock clock, long seed,
                           Duration referenceWindow, double volatilityBps, double meanReversion) {
        this.clock = clock;
        this.random = new Random(seed);
        this.referenceWindow = referenceWindow;
        this.volatility = volatilityBps / 10_000d;
        this.meanReversion = meanReversion;
        initialRates.forEach((pair, rate) -> {
            PairState state = new PairState(pair, rate);
            pairs.put(pair, state);
            state.record(rate, clock.instant());
        });
    }

    /** Advances every pair one step and returns the new observations. */
    public synchronized List<RateObservation> tick() {
        Instant now = clock.instant();
        List<RateObservation> out = new ArrayList<>();
        for (PairState state : pairs.values()) {
            double current = state.current.doubleValue();
            double anchor = state.anchor.doubleValue();
            double next = current + random.nextGaussian() * volatility * current + (anchor - current) * meanReversion;
            BigDecimal rate = new BigDecimal(next, MathContext.DECIMAL64).setScale(ExchangeRate.RATE_SCALE,
                    java.math.RoundingMode.HALF_EVEN);
            out.add(state.record(rate, now));
        }
        return out;
    }

    /** Sets a rate (either direction of a listed pair). Development/demo use only. */
    public synchronized RateObservation set(CurrencyPair pair, BigDecimal rate) {
        if (rate.signum() <= 0) {
            throw new IllegalArgumentException("Rate must be positive");
        }
        PairState state = pairs.get(pair);
        if (state != null) {
            state.anchor = rate;
            return state.record(rate, clock.instant());
        }
        state = pairs.get(pair.inverse());
        if (state == null) {
            throw unsupported(pair);
        }
        BigDecimal inverse = BigDecimal.ONE.divide(rate, MathContext.DECIMAL64);
        state.anchor = inverse;
        return state.record(inverse, clock.instant()).inverse();
    }

    @Override
    public synchronized Optional<RateObservation> observe(CurrencyPair pair) {
        PairState state = pairs.get(pair);
        if (state != null) {
            return Optional.of(state.latest);
        }
        state = pairs.get(pair.inverse());
        return state == null ? Optional.empty() : Optional.of(state.latest.inverse());
    }

    @Override
    public synchronized List<RateObservation> snapshot() {
        return pairs.values().stream().map(s -> s.latest).toList();
    }

    public static DomainException unsupported(CurrencyPair pair) {
        return new DomainException("UNSUPPORTED_PAIR", "Pair " + pair + " is not supported");
    }

    private final class PairState {
        private final CurrencyPair pair;
        private final Deque<Point> history = new ArrayDeque<>();
        private BigDecimal anchor;
        private BigDecimal current;
        private RateObservation latest;

        PairState(CurrencyPair pair, BigDecimal initial) {
            this.pair = pair;
            this.anchor = initial;
            this.current = initial;
        }

        RateObservation record(BigDecimal rate, Instant at) {
            Instant cutoff = at.minus(referenceWindow);
            while (!history.isEmpty() && history.peekFirst().at().isBefore(cutoff)) {
                history.removeFirst();
            }
            BigDecimal reference = history.isEmpty()
                    ? rate
                    : history.stream().map(Point::rate).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(history.size()), MathContext.DECIMAL64);
            current = rate;
            latest = new RateObservation(new ExchangeRate(pair, rate), new ExchangeRate(pair, reference), at,
                    ++sequence);
            history.addLast(new Point(rate, at));
            return latest;
        }
    }

    private record Point(BigDecimal rate, Instant at) {
    }
}
