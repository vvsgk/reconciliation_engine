package com.vvsgk.reconciliation_engine.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public record ReplayRequest(
        @NotBlank String accountId,
        Instant until
) {
}