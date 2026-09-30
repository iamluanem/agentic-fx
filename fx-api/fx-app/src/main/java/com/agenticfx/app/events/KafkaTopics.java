package com.agenticfx.app.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the domain-event topic (created by KafkaAdmin at startup; broker auto-creation stays disabled).
 * Partitioned by goal id, so the events of one goal are consumed in order.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentic-fx.events.broker", havingValue = "kafka", matchIfMissing = true)
public class KafkaTopics {

    @Bean
    NewTopic domainEventsTopic(EventsProperties properties) {
        return TopicBuilder.name(properties.topic()).partitions(6).replicas(1).build();
    }
}
