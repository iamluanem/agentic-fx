package com.agenticfx.app.config;

import com.agenticfx.app.security.UserAccounts;
import com.agenticfx.core.application.GoalService;
import com.agenticfx.core.goal.StrategyType;
import com.agenticfx.core.mandate.ExecutionMode;
import com.agenticfx.core.money.Currency;
import com.agenticfx.core.money.Money;
import com.agenticfx.core.port.Repositories;
import com.agenticfx.core.port.Transactions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Map;
import java.util.UUID;

/**
 * Dev profile only. Seeds the demonstration scenario, idempotently:
 * <ul>
 *   <li>customer demo@agentic.fx with BRL 30,000 · EUR 500 · USD 1,000</li>
 *   <li>active goal "Europe Trip": acquire €3,000 by 20 October, ACCUMULATE, spend at most R$18,000,
 *       R$4,000 per transaction, approval above R$2,500 (HUMAN_IN_THE_LOOP)</li>
 * </ul>
 * The goal is created through GoalService and activated explicitly, exactly as the UI does, so the audit trail
 * starts with the same "drafted → activated" events a real user would produce.
 */
@Component
@Profile("dev")
public class DevUserSeeder implements ApplicationRunner {

    public static final UUID DEMO_USER_ID = UUID.fromString("00000000-0000-0000-0000-00000000d3a0");
    private static final Logger log = LoggerFactory.getLogger(DevUserSeeder.class);

    private final UserAccounts users;
    private final GoalService goals;
    private final Repositories.Bundle repos;
    private final Transactions tx;
    private final Clock clock;
    private final AgenticFxProperties properties;
    private final String email;
    private final String password;
    private final boolean seedGoal;

    public DevUserSeeder(UserAccounts users, GoalService goals, Repositories.Bundle repos, Transactions tx,
                         Clock clock, AgenticFxProperties properties,
                         @Value("${agentic-fx.demo.email:demo@agentic.fx}") String email,
                         @Value("${agentic-fx.demo.password:demo-password}") String password,
                         @Value("${agentic-fx.demo.seed-goal:true}") boolean seedGoal) {
        this.users = users;
        this.goals = goals;
        this.repos = repos;
        this.tx = tx;
        this.clock = clock;
        this.properties = properties;
        this.email = email;
        this.password = password;
        this.seedGoal = seedGoal;
    }

    @Override
    public void run(ApplicationArguments args) {
        UUID userId = users.findByEmail(email).map(UserAccounts.UserRecord::id).orElseGet(() -> {
            users.createCustomer(DEMO_USER_ID, email, "Demo User", password, Map.of(
                    Currency.BRL, Money.of(30000, Currency.BRL),
                    Currency.EUR, Money.of(500, Currency.EUR),
                    Currency.USD, Money.of(1000, Currency.USD)));
            log.info("Seeded demo user {}", email);
            return DEMO_USER_ID;
        });
        if (!seedGoal || !tx.inTransaction(() -> repos.goals().findByUser(userId)).isEmpty()) {
            return;
        }
        var created = goals.createDraft(new GoalService.CreateGoal(userId, "Europe Trip", Currency.BRL, Currency.EUR,
                new BigDecimal("3000"), nextOctober20(), new BigDecimal("18000"), StrategyType.ACCUMULATE, null,
                null, null,
                new GoalService.MandateTerms(new BigDecimal("18000"), new BigDecimal("4000"), new BigDecimal("2500"),
                        true, ExecutionMode.HUMAN_IN_THE_LOOP)));
        goals.activate(created.goal().id(), userId);
        log.info("Seeded demo goal {} (deadline {})", created.goal().id(), created.goal().deadline());
    }

    private LocalDate nextOctober20() {
        LocalDate today = LocalDate.now(clock.withZone(properties.policy().businessZone()));
        LocalDate candidate = MonthDay.of(10, 20).atYear(today.getYear());
        return candidate.isBefore(today.plusDays(7)) ? candidate.plusYears(1) : candidate;
    }
}
