package com.agenticfx.app.config;

import com.agenticfx.app.provider.RevolutFxProvider;
import com.agenticfx.app.provider.WiseFxProvider;
import com.agenticfx.core.application.ExecutionService;
import com.agenticfx.core.application.GoalService;
import com.agenticfx.core.application.StrategyRunner;
import com.agenticfx.core.fx.FxProvider;
import com.agenticfx.core.fx.MockFxProvider;
import com.agenticfx.core.market.MarketSimulator;
import com.agenticfx.core.policy.DeterministicPolicyEngine;
import com.agenticfx.core.policy.Policy;
import com.agenticfx.core.policy.PolicyConfig;
import com.agenticfx.core.policy.PolicyEngine;
import com.agenticfx.core.port.EventPublisher;
import com.agenticfx.core.port.Metrics;
import com.agenticfx.core.port.Repositories;
import com.agenticfx.core.port.RequestContext;
import com.agenticfx.core.port.Transactions;
import com.agenticfx.core.strategy.StrategyEngine;
import com.agenticfx.core.strategy.StrategySettings;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/** Wires the framework-free core into Spring. The core itself has no knowledge of this module. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class CoreConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RequestContext requestContext() {
        return () -> MDC.get("correlationId");
    }

    @Bean
    PolicyConfig policyConfig(AgenticFxProperties properties) {
        var p = properties.policy();
        return new PolicyConfig(p.targetOvershootTolerance(), p.maxRateSlippageAfterApproval(), p.businessZone());
    }

    @Bean
    PolicyEngine policyEngine(PolicyConfig config, Clock clock) {
        return new DeterministicPolicyEngine(Policy.standard(), config, clock);
    }

    @Bean
    MarketSimulator marketSimulator(AgenticFxProperties properties, Clock clock) {
        var m = properties.market();
        return new MarketSimulator(MarketSimulator.defaultRates(), clock, m.seed(), m.referenceWindow(),
                m.volatilityBps(), m.meanReversion());
    }

    @Bean
    MockFxProvider mockFxProvider(MarketSimulator market, AgenticFxProperties properties, Clock clock) {
        return new MockFxProvider(market, properties.provider().mockFeeRate(), properties.execution().quoteTtl(), clock);
    }

    /** The provider used for quotes and execution. Real providers fail fast when credentials are missing. */
    @Bean
    FxProvider fxProvider(AgenticFxProperties properties, MockFxProvider mock) {
        return switch (properties.provider().name()) {
            case "mock" -> mock;
            case "wise" -> new WiseFxProvider(System.getenv("WISE_API_TOKEN"), System.getenv("WISE_PROFILE_ID"));
            case "revolut" -> new RevolutFxProvider(System.getenv("REVOLUT_API_TOKEN"));
            default -> throw new IllegalStateException("Unknown provider " + properties.provider().name());
        };
    }

    @Bean
    Repositories.Bundle repositories(Repositories.Goals goals, Repositories.Mandates mandates,
                                     Repositories.Proposals proposals, Repositories.Approvals approvals,
                                     Repositories.Conversions conversions, Repositories.Accounts accounts,
                                     Repositories.Quotes quotes, Repositories.PolicyEvaluations evaluations,
                                     Repositories.AgentDecisions decisions, Repositories.AuditLog audit) {
        return new Repositories.Bundle(goals, mandates, proposals, approvals, conversions, accounts, quotes,
                evaluations, decisions, audit);
    }

    @Bean
    ExecutionService executionService(Repositories.Bundle repos, PolicyEngine policy, FxProvider provider,
                                      EventPublisher events, Transactions tx, Metrics metrics,
                                      RequestContext context, Clock clock, AgenticFxProperties properties) {
        return new ExecutionService(repos, policy, provider, events, tx, metrics, context, clock,
                properties.execution().approvalTtl());
    }

    @Bean
    GoalService goalService(Repositories.Bundle repos, ExecutionService execution, EventPublisher events,
                            Transactions tx, Metrics metrics, RequestContext context, Clock clock,
                            AgenticFxProperties properties) {
        return new GoalService(repos, execution, events, tx, metrics, context, clock,
                properties.policy().businessZone());
    }

    @Bean
    StrategyEngine strategyEngine(AgenticFxProperties properties) {
        var s = properties.strategy();
        return new StrategyEngine(new StrategySettings(s.feeEstimate(), s.roundingIncrement(), s.minTranche(),
                s.fullStrengthMultiple(), s.cooldown()));
    }

    @Bean
    StrategyRunner strategyRunner(Repositories.Bundle repos, StrategyEngine engine, ExecutionService execution,
                                  EventPublisher events, Transactions tx, Metrics metrics, RequestContext context,
                                  Clock clock, AgenticFxProperties properties) {
        return new StrategyRunner(repos, engine, execution, events, tx, metrics, context, clock,
                properties.policy().businessZone());
    }
}
