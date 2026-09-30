package com.agenticfx.app.events;

/**
 * Broker abstraction so the outbox relay does not depend on a specific technology.
 * Implementations: {@link KafkaEventBroker} (default), {@link InMemoryEventBroker} (tests, single process),
 * {@link KinesisEventBroker} (AWS placeholder).
 */
public interface EventBroker {

    /** Publishes synchronously; returns only once the broker acknowledged the message. */
    void publish(String key, String envelopeJson);
}
