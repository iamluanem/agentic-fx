package com.agenticfx.app.events;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single-process broker: delivers each event on one background thread (preserving order) after the relay
 * transaction that published it. Used for tests and for running without Kafka.
 */
@Component
@ConditionalOnProperty(name = "agentic-fx.events.broker", havingValue = "in-memory")
public class InMemoryEventBroker implements EventBroker, DisposableBean {

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "in-memory-broker");
        t.setDaemon(true);
        return t;
    });
    private final EventDispatcher dispatcher;

    public InMemoryEventBroker(EventDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void publish(String key, String envelopeJson) {
        worker.execute(() -> dispatcher.dispatch(envelopeJson));
    }

    @Override
    public void destroy() {
        worker.shutdownNow();
    }
}
