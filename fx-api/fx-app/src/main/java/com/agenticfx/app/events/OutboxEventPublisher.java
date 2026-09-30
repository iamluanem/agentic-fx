package com.agenticfx.app.events;

import com.agenticfx.app.persistence.JsonCodec;
import com.agenticfx.core.event.DomainEvent;
import com.agenticfx.core.port.EventPublisher;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;

/** Writes domain events to the outbox table inside the caller's transaction. */
@Component
public class OutboxEventPublisher implements EventPublisher {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    public OutboxEventPublisher(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void publish(DomainEvent event) {
        jdbc.sql("""
                        INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload,
                            correlation_id, occurred_at)
                        VALUES (:id, :aggregateType, :aggregateId, :type, CAST(:payload AS jsonb), :correlationId,
                            :occurredAt)""")
                .param("id", event.eventId())
                .param("aggregateType", event instanceof DomainEvent.FxRateUpdated ? "market" : "goal")
                .param("aggregateId", EventEnvelope.aggregateId(event))
                .param("type", event.type())
                .param("payload", json.write(EventEnvelope.of(event, MDC.get("correlationId"))))
                .param("correlationId", MDC.get("correlationId"))
                .param("occurredAt", event.occurredAt().atOffset(ZoneOffset.UTC))
                .update();
    }
}
