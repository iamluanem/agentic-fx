package com.agenticfx.app.events;

import com.agenticfx.core.event.DomainEvent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Wire format for domain events: a small header plus the event as {@code payload}. The payload's Java type is
 * resolved from {@code type} against the sealed {@link DomainEvent} hierarchy only, so a message can never
 * instantiate an arbitrary class.
 */
public record EventEnvelope(UUID eventId, String type, Instant occurredAt, String key, String correlationId,
                            DomainEvent payload) {

    static final Map<String, Class<?>> TYPES = Arrays.stream(DomainEvent.class.getPermittedSubclasses())
            .collect(Collectors.toMap(Class::getSimpleName, Function.identity()));

    public static EventEnvelope of(DomainEvent event, String correlationId) {
        return new EventEnvelope(event.eventId(), event.type(), event.occurredAt(), event.key(), correlationId, event);
    }

    /** Stable UUID for the outbox row: the goal id, or a name-based UUID for market pairs. */
    static UUID aggregateId(DomainEvent event) {
        try {
            return UUID.fromString(event.key());
        } catch (IllegalArgumentException notAUuid) {
            return UUID.nameUUIDFromBytes(event.key().getBytes(StandardCharsets.UTF_8));
        }
    }
}
