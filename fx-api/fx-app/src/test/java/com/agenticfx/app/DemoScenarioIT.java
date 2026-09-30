package com.agenticfx.app;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The demonstration scenario end to end over HTTP, with the real database and the in-process broker. */
@TestPropertySource(properties = "agentic-fx.events.broker=in-memory")
class DemoScenarioIT extends IntegrationTestBase {

    @Test
    void marketMoveExecutesThenLargerMoveNeedsApprovalThenExecutes() {
        Customer c = newCustomer();
        UUID goalId = demoGoal(c);

        // 1–5: +1.15% → agent proposes R$2,000 → ALLOW → executes automatically.
        moveRate("1.0115");
        JsonNode afterFirst = awaitGoal(c, goalId, g -> g.path("conversions").size() == 1);
        assertEquals(2000.0, afterFirst.path("conversions").get(0).path("sourceAmount").path("amount").asDouble());
        assertEquals(28000.0, balance(c, "BRL"));

        // 6–8: +1.85% → agent proposes R$3,500 → REQUIRE_APPROVAL.
        moveRate("1.0185");
        JsonNode waiting = awaitGoal(c, goalId, g -> !g.path("pendingApprovalId").isNull()
                && !g.path("pendingApprovalId").isMissingNode());
        String approvalId = waiting.path("pendingApprovalId").asText();
        JsonNode approvals = get(c.token(), "/approvals").getBody();
        assertEquals("REQUIRE_APPROVAL", approvals.get(0).path("policy").path("decision").asText());
        assertEquals(3500.0, approvals.get(0).path("proposal").path("quote").path("sourceAmount").path("amount").asDouble());

        // 9–11: user approves → quote revalidated → policy re-run → executes.
        String key = UUID.randomUUID().toString();
        var approved = post(c.token(), "/approvals/" + approvalId + "/approve", null, key);
        assertEquals(200, approved.getStatusCode().value(), String.valueOf(approved.getBody()));
        assertEquals("COMPLETED", approved.getBody().path("proposal").path("status").asText());
        assertEquals("ALLOW", approved.getBody().path("policy").path("decision").asText());

        // Retrying the same request with the same Idempotency-Key replays the stored result.
        var replay = post(c.token(), "/approvals/" + approvalId + "/approve", null, key);
        assertEquals(200, replay.getStatusCode().value());
        assertEquals("true", replay.getHeaders().getFirst("Idempotent-Replay"));

        // 12: dashboard data.
        assertEquals(24500.0, balance(c, "BRL"));
        JsonNode goal = get(c.token(), "/goals/" + goalId).getBody();
        assertEquals(2, goal.path("conversions").size());
        assertEquals(5500.0, goal.path("goal").path("spent").path("amount").asDouble());

        // 13: audit timeline shows everything, in order, each step tied to mandate v1.
        List<String> types = new ArrayList<>();
        get(c.token(), "/goals/" + goalId + "/audit").getBody().forEach(e -> types.add(e.path("type").asText()));
        assertInOrder(types, "GOAL_CREATED", "MANDATE_ACTIVATED", "MARKET_CONDITION_DETECTED", "EXECUTION_PROPOSED",
                "POLICY_EVALUATED", "CONVERSION_COMPLETED", "MARKET_CONDITION_DETECTED", "EXECUTION_PROPOSED",
                "POLICY_EVALUATED", "APPROVAL_REQUESTED", "APPROVAL_GRANTED", "QUOTE_REVALIDATED", "POLICY_EVALUATED",
                "CONVERSION_COMPLETED");
        get(c.token(), "/goals/" + goalId + "/audit").getBody().forEach(e -> {
            if (!e.path("proposalId").isNull()) {
                assertEquals(1, e.path("mandateVersion").asInt(), e.toString());
            }
        });
    }

    @Test
    void agentToolsCanProposeButPolicyStillDecides() {
        Customer c = newCustomer();
        UUID goalId = demoGoal(c);
        String agentToken = post(c.token(), "/auth/delegate", null, null).getBody().path("accessToken").asText();

        var denied = post(agentToken, "/agent/tools/proposals",
                java.util.Map.of("goalId", goalId, "sourceAmount", 4500, "rationale", "LLM wants more"), "k-1");
        assertEquals(201, denied.getStatusCode().value());
        assertEquals("DENY", denied.getBody().path("policy").path("decision").asText());
        assertEquals("MAX_TRANSACTION_AMOUNT_EXCEEDED", denied.getBody().path("policy").path("reasonCodes").get(0).asText());
        assertEquals(30000.0, balance(c, "BRL"));

        var small = post(agentToken, "/agent/tools/proposals",
                java.util.Map.of("goalId", goalId, "sourceAmount", 1000, "rationale", "small step", "requireApproval", true),
                "k-2");
        assertEquals("AWAITING_APPROVAL", small.getBody().path("proposal").path("status").asText(),
                "the agent can make execution more conservative, never less");
    }

    private double balance(Customer c, String currency) {
        for (JsonNode b : get(c.token(), "/accounts/me/balances").getBody()) {
            if (b.path("currency").asText().equals(currency)) {
                return b.path("available").path("amount").asDouble();
            }
        }
        return 0;
    }

    private static void assertInOrder(List<String> actual, String... expected) {
        int from = 0;
        for (String e : expected) {
            int at = actual.subList(from, actual.size()).indexOf(e);
            assertTrue(at >= 0, "missing " + e + " after position " + from + " in " + actual);
            from += at + 1;
        }
    }
}
