package com.agenticfx.app;

import com.agenticfx.app.market.MarketTicker;
import com.agenticfx.app.security.UserAccounts;
import com.agenticfx.core.market.MarketSimulator;
import com.agenticfx.core.money.Currency;
import com.agenticfx.core.money.CurrencyPair;
import com.agenticfx.core.money.Money;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Boots the real application against a real PostgreSQL (Testcontainers). Market ticks are effectively disabled
 * (one per hour, no noise) so tests move rates explicitly through {@link MarketTicker#set}, which takes the same
 * path as production ticks: snapshot → outbox → broker → StrategyRunner.
 * Subclasses choose the broker; the default is the in-process one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "agentic-fx.security.jwt-secret=integration-test-secret-at-least-32-bytes!!",
        "agentic-fx.market.tick-interval=PT1H",
        "agentic-fx.market.volatility-bps=0",
        "agentic-fx.market.mean-reversion=0",
        "agentic-fx.events.relay-interval=PT0.2S",
        "spring.data.redis.port=1",
})
public abstract class IntegrationTestBase {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    protected static final CurrencyPair BRL_EUR = CurrencyPair.parse("BRL_EUR");

    @Autowired protected TestRestTemplate http;
    @Autowired protected UserAccounts users;
    @Autowired protected MarketTicker ticker;
    @Autowired protected MarketSimulator market;
    @Autowired protected JdbcClient jdbc;

    /** A fresh customer with the demo balances; returns a CUSTOMER bearer token. */
    protected Customer newCustomer() {
        UUID id = UUID.randomUUID();
        String email = "it-" + id + "@agentic.fx";
        users.createCustomer(id, email, "IT User", "password-123", Map.of(
                Currency.BRL, Money.of(30000, Currency.BRL),
                Currency.EUR, Money.of(500, Currency.EUR),
                Currency.USD, Money.of(1000, Currency.USD)));
        JsonNode token = post(null, "/auth/token", Map.of("email", email, "password", "password-123"), null).getBody();
        return new Customer(id, token.path("accessToken").asText());
    }

    protected record Customer(UUID id, String token) {
    }

    /** The seeded demo goal, created and activated through the API. */
    protected UUID demoGoal(Customer c) {
        var body = Map.of(
                "name", "Europe Trip", "sourceCurrency", "BRL", "targetCurrency", "EUR", "targetAmount", 3000,
                "deadline", java.time.LocalDate.now().plusDays(20).toString(), "maxBudget", 18000,
                "strategyType", "ACCUMULATE",
                "mandate", Map.of("maxTotalSpend", 18000, "maxTransactionAmount", 4000, "approvalThreshold", 2500,
                        "allowPartialExecution", true, "executionMode", "HUMAN_IN_THE_LOOP"));
        ResponseEntity<JsonNode> created = post(c.token(), "/goals", body, UUID.randomUUID().toString());
        assertEquals(201, created.getStatusCode().value(), String.valueOf(created.getBody()));
        UUID goalId = UUID.fromString(created.getBody().path("goal").path("id").asText());
        assertEquals(200, post(c.token(), "/goals/" + goalId + "/activate", null, UUID.randomUUID().toString())
                .getStatusCode().value());
        return goalId;
    }

    /**
     * Moves BRL→EUR to {@code factor} × the reference rate. A few ticks are recorded first (as the live market
     * does every 5 s) so that the reference the move is measured against is stable.
     */
    protected void moveRate(String factor) {
        for (int i = 0; i < 20; i++) {
            ticker.tick();
        }
        BigDecimal reference = market.observe(BRL_EUR).orElseThrow().reference().rate();
        ticker.set(BRL_EUR, reference.multiply(new BigDecimal(factor)));
    }

    protected ResponseEntity<JsonNode> get(String token, String path) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token, null)), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> post(String token, String path, Object body, String idempotencyKey) {
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(token, idempotencyKey)), JsonNode.class);
    }

    protected static HttpHeaders headers(String token, String idempotencyKey) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            h.setBearerAuth(token);
        }
        if (idempotencyKey != null) {
            h.set("Idempotency-Key", idempotencyKey);
        }
        return h;
    }

    protected JsonNode awaitGoal(Customer c, UUID goalId, java.util.function.Predicate<JsonNode> condition) {
        JsonNode[] last = new JsonNode[1];
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).until(() -> {
            last[0] = get(c.token(), "/goals/" + goalId).getBody();
            return last[0] != null && condition.test(last[0]);
        });
        return last[0];
    }
}
