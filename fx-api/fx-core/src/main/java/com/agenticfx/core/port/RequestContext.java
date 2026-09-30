package com.agenticfx.core.port;

/** Ambient request context (correlation id), supplied by the application (e.g. from MDC). */
@FunctionalInterface
public interface RequestContext {

    String correlationId();

    RequestContext NONE = () -> null;
}
