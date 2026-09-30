package com.agenticfx.core.application;

import com.agenticfx.core.audit.AuditEvent;
import com.agenticfx.core.audit.AuditEventType;
import com.agenticfx.core.port.RequestContext;

import java.time.Clock;
import java.util.UUID;

/** Small helper so every audit record carries time and correlation id consistently. */
final class Audit {

    private final Clock clock;
    private final RequestContext context;

    Audit(Clock clock, RequestContext context) {
        this.clock = clock;
        this.context = context;
    }

    AuditEvent.Builder event(AuditEventType type, UUID userId, String actor) {
        return AuditEvent.builder(type, userId, clock.instant())
                .actor(actor)
                .correlationId(context.correlationId());
    }

    static String user(UUID userId) {
        return "user:" + userId;
    }
}
