package com.agenticfx.app.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * AWS Kinesis adapter placeholder. Selecting {@code agentic-fx.events.broker=kinesis} fails fast at startup
 * until the AWS SDK dependency and stream configuration are added; the outbox relay and consumers need no
 * change. Partition key = {@code key} (goal id), which preserves per-goal ordering like Kafka.
 */
@Component
@ConditionalOnProperty(name = "agentic-fx.events.broker", havingValue = "kinesis")
public class KinesisEventBroker implements EventBroker {

    public KinesisEventBroker() {
        throw new IllegalStateException(
                "Kinesis broker is not configured in this build: add software.amazon.awssdk:kinesis and "
                        + "implement PutRecord with partitionKey = event key");
    }

    @Override
    public void publish(String key, String envelopeJson) {
        throw new UnsupportedOperationException("Kinesis broker not configured");
    }
}
