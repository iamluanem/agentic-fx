package com.agenticfx.app.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@ConditionalOnProperty(name = "agentic-fx.events.broker", havingValue = "kafka", matchIfMissing = true)
public class KafkaEventBroker implements EventBroker {

    private final KafkaTemplate<String, String> kafka;
    private final EventsProperties properties;
    private final EventDispatcher dispatcher;

    public KafkaEventBroker(KafkaTemplate<String, String> kafka, EventsProperties properties,
                            EventDispatcher dispatcher) {
        this.kafka = kafka;
        this.properties = properties;
        this.dispatcher = dispatcher;
    }

    @Override
    public void publish(String key, String envelopeJson) {
        try {
            kafka.send(properties.topic(), key, envelopeJson).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka did not acknowledge the event", e);
        }
    }

    /** Offsets are committed after the listener returns; the dispatcher deduplicates redeliveries. */
    @KafkaListener(topics = "${agentic-fx.events.topic:fx.domain-events}", groupId = "fx-api")
    public void onMessage(String envelopeJson) {
        dispatcher.dispatch(envelopeJson);
    }
}
