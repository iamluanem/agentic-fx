package com.agenticfx.app.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Relays committed outbox rows to the broker. Rows are claimed with {@code FOR UPDATE SKIP LOCKED}, so several
 * instances can relay concurrently without publishing the same row twice; a failed publish rolls the claim back
 * and the row is retried on the next run (at-least-once, deduplicated by consumers).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final EventBroker broker;
    private final EventsProperties properties;

    public OutboxRelay(JdbcClient jdbc, TransactionTemplate tx, EventBroker broker, EventsProperties properties) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.broker = broker;
        this.properties = properties;
    }

    record Row(UUID id, String key, String payload) {
    }

    @Scheduled(fixedDelayString = "${agentic-fx.events.relay-interval:PT0.5S}")
    public void relay() {
        try {
            Integer published = tx.execute(status -> {
                List<Row> rows = jdbc.sql("""
                                SELECT id, aggregate_id::text AS key, payload::text AS payload FROM outbox_events
                                WHERE published_at IS NULL ORDER BY occurred_at LIMIT :limit
                                FOR UPDATE SKIP LOCKED""")
                        .param("limit", properties.relayBatchSize())
                        .query((rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("key"),
                                rs.getString("payload")))
                        .list();
                for (Row row : rows) {
                    broker.publish(row.key(), row.payload());
                    jdbc.sql("UPDATE outbox_events SET published_at = now(), attempts = attempts + 1 WHERE id = :id")
                            .param("id", row.id()).update();
                }
                return rows.size();
            });
            if (published != null && published > 0) {
                log.debug("Relayed {} outbox events", published);
            }
        } catch (RuntimeException e) {
            log.warn("Outbox relay failed; will retry: {}", e.getMessage());
        }
    }
}
