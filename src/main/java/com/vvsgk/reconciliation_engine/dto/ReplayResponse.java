package com.vvsgk.reconciliation_engine.dto;

import com.vvsgk.reconciliation_engine.reconciliation.ReconciliationResult;

import java.util.List;

public record ReplayResponse(
        ReconciliationResult reconciliation,
        List<String> normalizedOrdering,
        String stateHash,
        String replayRunId
) {
}