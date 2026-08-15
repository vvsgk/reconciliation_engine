package com.vvsgk.reconciliation_engine.reconciliation;
import com.vvsgk.reconciliation_engine.entity.Event;
import java.math.BigDecimal;
import java.util.List;
public record ReconciliationResult(Event resolvedEvent, ResolutionMethod resolutionMethod,
                                   List<String> consideredEventIds, BigDecimal finalBalance) { }
