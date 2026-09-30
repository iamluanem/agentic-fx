package com.agenticfx.core.mandate;

import com.agenticfx.core.Scenario;
import com.agenticfx.core.money.Money;
import com.agenticfx.core.shared.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.agenticfx.core.money.Currency.BRL;
import static com.agenticfx.core.money.Currency.EUR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMandateTest {

    private final Scenario s = new Scenario();

    @Test
    void draftMustBeExplicitlyActivated() {
        var goal = s.activeGoal();
        var mandate = AgentMandate.draft(goal.id(), s.userId, s.permissions(), s.mode, s.deadline, s.now);
        assertFalse(mandate.isActive());
        mandate.activate(s.now);
        assertTrue(mandate.isActive());
        assertEquals(s.now, mandate.activatedAt());
    }

    @Test
    void amendingCreatesNextVersionAndKeepsIdentity() {
        var v1 = s.activeMandate(s.activeGoal());
        var v2 = v1.amend(s.permissions(), ExecutionMode.AUTONOMOUS, s.deadline, s.now);
        assertEquals(v1.id(), v2.id());
        assertEquals(2, v2.version());
        assertEquals(MandateStatus.DRAFT, v2.status());
        assertTrue(v1.isActive(), "old version stays in force until the new one is activated");
        v1.supersede(s.now);
        v2.activate(s.now);
        assertThrows(InvalidStateTransitionException.class, () -> v1.activate(s.now));
        assertThrows(InvalidStateTransitionException.class,
                () -> v1.amend(s.permissions(), s.mode, s.deadline, s.now));
    }

    @Test
    void validityIsInclusiveOfLastDay() {
        var mandate = s.activeMandate(s.activeGoal());
        assertFalse(mandate.isExpiredOn(LocalDate.parse("2026-10-20")));
        assertTrue(mandate.isExpiredOn(LocalDate.parse("2026-10-21")));
    }

    @Test
    void permissionsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new MandatePermissions(BRL, EUR,
                Money.of(1000, BRL), Money.of(2000, BRL), null, true));
        assertThrows(IllegalArgumentException.class, () -> new MandatePermissions(BRL, BRL,
                Money.of(1000, BRL), Money.of(500, BRL), null, true));
        assertThrows(IllegalArgumentException.class, () -> new MandatePermissions(BRL, EUR,
                Money.of(1000, BRL), Money.of(500, EUR), null, true));
        assertThrows(IllegalArgumentException.class, () -> new MandatePermissions(BRL, EUR,
                Money.of(1000, BRL), Money.of(500, BRL), Money.of(-1, BRL), true));
    }

    @Test
    void suspendAndResume() {
        var mandate = s.activeMandate(s.activeGoal());
        mandate.suspend();
        assertFalse(mandate.isActive());
        mandate.resume();
        assertTrue(mandate.isActive());
        mandate.revoke(s.now);
        assertThrows(InvalidStateTransitionException.class, mandate::resume);
    }
}
