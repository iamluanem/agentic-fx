package com.agenticfx.app.market;

import com.agenticfx.core.event.DomainEvent;
import com.agenticfx.core.market.MarketSimulator;
import com.agenticfx.core.market.RateObservation;
import com.agenticfx.core.money.CurrencyPair;
import com.agenticfx.core.port.EventPublisher;
import com.agenticfx.core.port.Transactions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Drives the simulated market: each tick is persisted as a snapshot and published as {@code FxRateUpdated}
 * through the outbox, which is what wakes the Strategy Engine (Market Event → Strategy → Proposal → Policy).
 */
@Component
@ConditionalOnProperty(name = "agentic-fx.market.simulator-enabled", havingValue = "true", matchIfMissing = true)
public class MarketTicker {

    private final MarketSimulator market;
    private final Transactions tx;
    private final EventPublisher events;
    private final JdbcClient jdbc;

    public MarketTicker(MarketSimulator market, Transactions tx, EventPublisher events, JdbcClient jdbc) {
        this.market = market;
        this.tx = tx;
        this.events = events;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${agentic-fx.market.tick-interval:PT5S}")
    public void tick() {
        record(market.tick(), "simulator");
    }

    /** Development/demo: set a rate and publish it like any other market move. */
    public RateObservation set(CurrencyPair pair, BigDecimal rate) {
        RateObservation observation = market.set(pair, rate);
        record(List.of(observation), "manual");
        return observation;
    }

    private void record(List<RateObservation> observations, String source) {
        tx.run(() -> observations.forEach(o -> {
            jdbc.sql("INSERT INTO fx_rate_snapshots (pair, rate, source, observed_at) VALUES (:pair, :rate, :source, :at)")
                    .param("pair", o.pair().code()).param("rate", o.rate().rate()).param("source", source)
                    .param("at", o.observedAt().atOffset(ZoneOffset.UTC))
                    .update();
            events.publish(new DomainEvent.FxRateUpdated(UUID.randomUUID(), o.observedAt(), o.pair().code(),
                    o.rate().rate(), o.reference().rate(), o.sequence()));
        }));
    }
}
