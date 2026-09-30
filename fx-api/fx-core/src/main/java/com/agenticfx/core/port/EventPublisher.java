package com.agenticfx.core.port;

import com.agenticfx.core.event.DomainEvent;

/** Records a domain event. Implementations write to the transactional outbox (never directly to the broker). */
public interface EventPublisher {
    void publish(DomainEvent event);
}
