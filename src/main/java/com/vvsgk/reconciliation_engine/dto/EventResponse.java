package com.vvsgk.reconciliation_engine.dto;
import com.vvsgk.reconciliation_engine.entity.Event;
import com.vvsgk.reconciliation_engine.reconciliation.ReconciliationResult;
public record EventResponse(Event event, ReconciliationResult reconciliation) { }
