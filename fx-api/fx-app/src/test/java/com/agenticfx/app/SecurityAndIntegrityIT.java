package com.agenticfx.app;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RBAC, idempotency, concurrency and database-level guarantees against the real stack. */
@TestPropertySource(properties = "agentic-fx.events.broker=in-memory")
class SecurityAndIntegrityIT extends IntegrationTestBase {

    @Test
    void unauthenticatedAndForgedRequestsAreRejected() {
        assertEquals(401, get(null, "/goals").getStatusCode().value());
        assertEquals(401, get("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.forged", "/goals").getStatusCode().value());
        assertEquals(401, post(null, "/auth/token",
                java.util.Map.of("email", "nobody@agentic.fx", "password", "wrong-password"), null).getStatusCode().value());
    }

    @Test
    void agentTokenCannotApproveOrManageGoals() {
        Customer c = newCustomer();
        UUID goalId = demoGoal(c);
        String agent = post(c.token(), "/auth/delegate", null, null).getBody().path("accessToken").asText();
        assertEquals(403, get(agent, "/approvals").getStatusCode().value());
        assertEquals(403, post(agent, "/approvals/" + UUID.randomUUID() + "/approve", null, "k").getStatusCode().value());
        assertEquals(403, post(agent, "/goals/" + goalId + "/pause", null, null).getStatusCode().value());
        assertEquals(403, post(agent, "/auth/delegate", null, null).getStatusCode().value());
        assertEquals(200, get(agent, "/agent/tools/goals/" + goalId).getStatusCode().value());
    }

    @Test
    void customersAreIsolated() {
        Customer a = newCustomer();
        Customer b = newCustomer();
        UUID goalOfA = demoGoal(a);
        assertEquals(404, get(b.token(), "/goals/" + goalOfA).getStatusCode().value());
        assertEquals(404, get(b.token(), "/goals/" + goalOfA + "/audit").getStatusCode().value());
        assertEquals(404, post(b.token(), "/goals/" + goalOfA + "/cancel", null, null).getStatusCode().value());
    }

    @Test
    void devEndpointsDoNotExistOutsideTheDevProfile() {
        Customer c = newCustomer();
        assertEquals(404, post(c.token(), "/dev/fx/rates",
                java.util.Map.of("pair", "BRL_EUR", "rate", 0.2), null).getStatusCode().value());
    }

    @Test
    void financialPostsRequireAnIdempotencyKeyReplayRetriesAndRejectReuse() {
        Customer c = newCustomer();
        UUID goalId = demoGoal(c);
        assertEquals(400, post(c.token(), "/goals/" + goalId + "/activate", null, null).getStatusCode().value());

        var body = java.util.Map.of("sourceCurrency", "BRL", "targetCurrency", "USD", "targetAmount", 1000,
                "deadline", java.time.LocalDate.now().plusDays(30).toString(), "maxBudget", 6000,
                "mandate", java.util.Map.of("maxTotalSpend", 6000, "maxTransactionAmount", 2000,
                        "executionMode", "HUMAN_IN_THE_LOOP"));
        String key = UUID.randomUUID().toString();
        var first = post(c.token(), "/goals", body, key);
        var retry = post(c.token(), "/goals", body, key);
        assertEquals(201, first.getStatusCode().value(), String.valueOf(first.getBody()));
        assertEquals(first.getBody().path("goal").path("id"), retry.getBody().path("goal").path("id"),
                "a retry returns the original result instead of creating a second goal");
        assertEquals("true", retry.getHeaders().getFirst("Idempotent-Replay"));

        var changed = new java.util.HashMap<String, Object>(body);
        changed.put("targetAmount", 2000);
        var reused = post(c.token(), "/goals", changed, key);
        assertEquals(409, reused.getStatusCode().value());
        assertEquals("IDEMPOTENCY_KEY_REUSED", reused.getBody().path("code").asText());
    }

    @Test
    void concurrentApprovalsExecuteExactlyOnce() throws Exception {
        Customer c = newCustomer();
        UUID goalId = demoGoal(c);
        String agent = post(c.token(), "/auth/delegate", null, null).getBody().path("accessToken").asText();
        post(agent, "/agent/tools/proposals", java.util.Map.of("goalId", goalId, "sourceAmount", 3500,
                "rationale", "test"), "race");
        String approvalId = get(c.token(), "/approvals").getBody().get(0).path("id").asText();

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> results = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return post(c.token(), "/approvals/" + approvalId + "/approve", null, UUID.randomUUID().toString());
            }));
        }
        start.countDown();
        int ok = 0;
        for (var f : results) {
            ok += f.get().getStatusCode().value() == 200 ? 1 : 0;
        }
        pool.shutdown();
        assertEquals(1, ok, "exactly one approval wins; the others get 409");
        long conversions = jdbc.sql("SELECT count(*) FROM fx_conversions WHERE goal_id = :g")
                .param("g", goalId).query(Long.class).single();
        assertEquals(1, conversions);
    }

    @Test
    void auditAndMandateTermsAreImmutableInTheDatabase() {
        Customer c = newCustomer();
        UUID goalId = demoGoal(c);
        assertThrows(Exception.class, () -> jdbc.sql("UPDATE audit_events SET summary = 'tampered' WHERE goal_id = :g")
                .param("g", goalId).update());
        assertThrows(Exception.class, () -> jdbc.sql("DELETE FROM audit_events WHERE goal_id = :g")
                .param("g", goalId).update());
        assertThrows(Exception.class, () -> jdbc.sql(
                        "UPDATE agent_mandates SET max_transaction_amount = 999999 WHERE goal_id = :g")
                .param("g", goalId).update());
        long events = jdbc.sql("SELECT count(*) FROM audit_events WHERE goal_id = :g").param("g", goalId)
                .query(Long.class).single();
        assertTrue(events >= 4);
    }
}
