package com.agenticfx.core.execution;

import com.agenticfx.core.Scenario;
import com.agenticfx.core.approval.ApprovalRequest;
import com.agenticfx.core.approval.ApprovalStatus;
import com.agenticfx.core.fx.FxQuote;
import com.agenticfx.core.money.Money;
import com.agenticfx.core.shared.DomainException;
import com.agenticfx.core.shared.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static com.agenticfx.core.money.Currency.BRL;
import static com.agenticfx.core.money.Currency.EUR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionLifecycleTest {

    private final Scenario s = new Scenario();

    @Test
    void quoteIsInternallyConsistent() {
        FxQuote quote = s.quote(Money.of(2000, BRL));
        assertEquals(Money.of(12, BRL), quote.fee());
        assertEquals(Money.of("328.02", EUR), quote.targetAmount());
        assertFalse(quote.isExpiredAt(Scenario.NOW.plusSeconds(29)));
        assertTrue(quote.isExpiredAt(Scenario.NOW.plusSeconds(30)));
    }

    @Test
    void approvalPathPinsTheApprovedRate() {
        var proposal = s.proposal(s.activeMandate(s.activeGoal()), 3500);
        proposal.awaitApproval("above threshold", s.now);
        assertFalse(proposal.isApproved());
        proposal.approve(s.now);
        assertTrue(proposal.isApproved());
        assertEquals(new BigDecimal("0.16500000"), proposal.approvedRate().orElseThrow().rate());
        proposal.startExecution(s.now);
        proposal.complete(s.now);
        assertTrue(proposal.status().isTerminal());
    }

    @Test
    void reApprovalClearsPreviousApproval() {
        var proposal = s.proposal(s.activeMandate(s.activeGoal()), 3500);
        proposal.awaitApproval("above threshold", s.now);
        proposal.approve(s.now);
        proposal.awaitApproval("rate moved", s.now);
        assertTrue(proposal.approvedRate().isEmpty());
    }

    @Test
    void cannotSkipApprovalOrExecuteTwice() {
        var proposal = s.proposal(s.activeMandate(s.activeGoal()), 3500);
        proposal.awaitApproval("above threshold", s.now);
        assertThrows(InvalidStateTransitionException.class, () -> proposal.startExecution(s.now));
        proposal.approve(s.now);
        proposal.startExecution(s.now);
        assertThrows(InvalidStateTransitionException.class, () -> proposal.startExecution(s.now));
    }

    @Test
    void requoteMustKeepAmountAndPair() {
        var proposal = s.proposal(s.activeMandate(s.activeGoal()), 2000);
        assertThrows(DomainException.class, () -> proposal.requote(s.quote(Money.of(2001, BRL)), s.now));
        proposal.requote(s.quote(Money.of(2000, BRL), new BigDecimal("0.166")), s.now);
        assertEquals(new BigDecimal("0.16600000"), proposal.quote().rate().rate());
    }

    @Test
    void rejectionRecordsSource() {
        var proposal = s.proposal(s.activeMandate(s.activeGoal()), 2000);
        proposal.reject(RejectionSource.POLICY, "MAX_TOTAL_SPEND_EXCEEDED", s.now);
        assertEquals(RejectionSource.POLICY, proposal.rejectionSource().orElseThrow());
        assertThrows(InvalidStateTransitionException.class, () -> proposal.approve(s.now));
    }

    @Test
    void approvalIsOwnerOnlyAndFinal() {
        var approval = ApprovalRequest.open(UUID.randomUUID(), UUID.randomUUID(), s.userId, s.now,
                s.now.plusSeconds(3600));
        var ex = assertThrows(DomainException.class, () -> approval.approve(UUID.randomUUID(), s.now));
        assertEquals("APPROVAL_FORBIDDEN", ex.code());
        approval.approve(s.userId, s.now);
        assertEquals(ApprovalStatus.APPROVED, approval.status());
        assertEquals("APPROVAL_ALREADY_DECIDED",
                assertThrows(DomainException.class, () -> approval.reject(s.userId, "no", s.now)).code());
    }

    @Test
    void expiredApprovalCannotBeGranted() {
        var approval = ApprovalRequest.open(UUID.randomUUID(), UUID.randomUUID(), s.userId, s.now,
                s.now.plusSeconds(60));
        assertEquals("APPROVAL_EXPIRED",
                assertThrows(DomainException.class, () -> approval.approve(s.userId, s.now.plusSeconds(60))).code());
    }
}
