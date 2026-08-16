package com.vvsgk.reconciliation_engine.stress;

import com.vvsgk.reconciliation_engine.dto.EventRequest;
import java.math.BigDecimal;
import java.time.Instant;

public class EventGenerator {
    public EventRequest random(String eventId, String accountId, Instant timestamp) {
        return new EventRequest(eventId, timestamp, accountId, new BigDecimal("1.00"), "USD", "stress");
    }
}
