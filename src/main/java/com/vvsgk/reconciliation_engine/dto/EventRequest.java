package com.vvsgk.reconciliation_engine.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;

public record EventRequest(
        @NotBlank String eventId,
        @NotNull Instant timestamp,
        @NotBlank String accountId,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal amount,
        @NotBlank String currency,
        @NotBlank String source) { }
