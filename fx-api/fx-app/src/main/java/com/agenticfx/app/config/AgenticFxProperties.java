package com.agenticfx.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;

/** Typed application settings under {@code agentic-fx.*}. */
@ConfigurationProperties("agentic-fx")
public record AgenticFxProperties(@DefaultValue Policy policy, @DefaultValue Execution execution,
                                  @DefaultValue Market market, @DefaultValue Provider provider,
                                  @DefaultValue Strategy strategy, @DefaultValue Security security) {

    public record Policy(
            @DefaultValue("0.01") BigDecimal targetOvershootTolerance,
            @DefaultValue("0.005") BigDecimal maxRateSlippageAfterApproval,
            @DefaultValue("America/Sao_Paulo") ZoneId businessZone) {
    }

    public record Execution(
            @DefaultValue("PT30S") Duration quoteTtl,
            @DefaultValue("PT24H") Duration approvalTtl,
            @DefaultValue("PT1M") Duration recoverAfter) {
    }

    /** Market simulator. Volatility in basis points per tick; mean reversion 0..1 per tick. */
    public record Market(
            @DefaultValue("true") boolean simulatorEnabled,
            @DefaultValue("PT5S") Duration tickInterval,
            @DefaultValue("PT60M") Duration referenceWindow,
            @DefaultValue("3") double volatilityBps,
            @DefaultValue("0.2") double meanReversion,
            @DefaultValue("20260930") long seed) {
    }

    /** Liquidity provider: {@code mock} (default), {@code wise} or {@code revolut}. */
    public record Provider(
            @DefaultValue("mock") String name,
            @DefaultValue("0.006") BigDecimal mockFeeRate) {
    }

    public record Strategy(
            @DefaultValue("0.006") BigDecimal feeEstimate,
            @DefaultValue("500") BigDecimal roundingIncrement,
            @DefaultValue("100") BigDecimal minTranche,
            @DefaultValue("4") BigDecimal fullStrengthMultiple,
            @DefaultValue("PT6H") Duration cooldown) {
    }

    /** {@code jwtSecret} must be at least 32 bytes; supplied via the JWT_SECRET environment variable. */
    public record Security(
            String jwtSecret,
            @DefaultValue("agentic-fx") String issuer,
            @DefaultValue("PT8H") Duration userTokenTtl,
            @DefaultValue("PT10M") Duration agentTokenTtl,
            @DefaultValue("600") int requestsPerMinute) {
    }
}
