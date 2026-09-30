package com.agenticfx.app.events;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("agentic-fx.events")
public record EventsProperties(
        @DefaultValue("kafka") String broker,
        @DefaultValue("fx.domain-events") String topic,
        @DefaultValue("PT0.5S") Duration relayInterval,
        @DefaultValue("100") int relayBatchSize) {
}
