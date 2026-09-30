package com.agenticfx.app;

import com.agenticfx.app.events.EventDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Outbox → Kafka → consumer → Strategy Engine, with a real broker; and consumer idempotency on redelivery. */
@TestPropertySource(properties = "agentic-fx.events.broker=kafka")
class KafkaEventFlowIT extends IntegrationTestBase {

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    static {
        KAFKA.start();
    }

    @Autowired
    EventDispatcher dispatcher;

    @Test
    void rateUpdateTravelsThroughKafkaAndTriggersTheAgent() {
        Customer c = newCustomer();
        UUID goalId = demoGoal(c);
        moveRate("1.0115");
        awaitGoal(c, goalId, g -> g.path("conversions").size() == 1);

        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql(
                "SELECT count(*) FROM outbox_events WHERE published_at IS NULL").query(Long.class).single() == 0);
        long consumed = jdbc.sql("SELECT count(*) FROM processed_events").query(Long.class).single();
        assertEquals(true, consumed > 0);
    }

    @Test
    void redeliveredEventsAreProcessedOnce() {
        Customer c = newCustomer();
        UUID goalId = demoGoal(c);
        moveRate("1.0115");
        awaitGoal(c, goalId, g -> g.path("conversions").size() == 1);

        // Replay every published FxRateUpdated envelope, as Kafka would after a consumer crash.
        var envelopes = jdbc.sql("SELECT payload::text FROM outbox_events WHERE event_type = 'FxRateUpdated'")
                .query(String.class).list();
        envelopes.forEach(dispatcher::dispatch);
        envelopes.forEach(dispatcher::dispatch);

        long proposals = jdbc.sql("SELECT count(*) FROM execution_proposals WHERE goal_id = :g")
                .param("g", goalId).query(Long.class).single();
        assertEquals(1, proposals, "duplicate deliveries must not create proposals");
    }
}
