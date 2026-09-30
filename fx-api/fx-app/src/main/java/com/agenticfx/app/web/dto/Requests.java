package com.agenticfx.app.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Request models with bean validation. Currency codes are validated again by the domain. */
public final class Requests {

    private Requests() {
    }

    private static final String CURRENCY = "BRL|EUR|USD|GBP";

    public record Login(@NotBlank @Email String email, @NotBlank @Size(max = 200) String password) {
    }

    public record MandateTerms(
            @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal maxTotalSpend,
            @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal maxTransactionAmount,
            @PositiveOrZero @Digits(integer = 15, fraction = 2) BigDecimal approvalThreshold,
            Boolean allowPartialExecution,
            @NotNull @Pattern(regexp = "HUMAN_IN_THE_LOOP|AUTONOMOUS") String executionMode) {
    }

    public record CreateGoal(
            @Size(max = 120) String name,
            @NotNull @Pattern(regexp = CURRENCY) String sourceCurrency,
            @NotNull @Pattern(regexp = CURRENCY) String targetCurrency,
            @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal targetAmount,
            @NotNull LocalDate deadline,
            @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal maxBudget,
            @Pattern(regexp = "TARGET_RATE|ACCUMULATE|DEADLINE_AWARE") String strategyType,
            @DecimalMin(value = "0", inclusive = false) BigDecimal targetRate,
            @Min(1) @Max(50) Integer plannedTranches,
            @Min(0) @Max(5000) Integer minImprovementBps,
            @NotNull @Valid MandateTerms mandate) {
    }

    public record Reject(@Size(max = 500) String reason) {
    }

    public record DevRate(@NotBlank @Pattern(regexp = "[A-Za-z]{3}[_/]?[A-Za-z]{3}") String pair,
                          @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal rate) {
    }

    /**
     * Proposal created through the agent tools. {@code rationale} and {@code marketContext} are free text from the
     * LLM: stored sanitised for explainability, never interpreted.
     */
    public record AgentProposal(
            @NotNull UUID goalId,
            @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal sourceAmount,
            @Size(max = 500) String marketContext,
            @Size(max = 500) String rationale,
            boolean requireApproval) {
    }
}
