package com.vvsgk.reconciliation_engine.reconciliation;

import com.vvsgk.reconciliation_engine.entity.Event;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ConflictResolverTests {
    private final ConflictResolver resolver = new ConflictResolver();

    private Event e(String id, Instant ts, String acc, String amt) {
        return new Event(id, ts, acc, new BigDecimal(amt), "USD", "t", Instant.now());
    }

    @Test
    void twoEventsWithinOneHour_higherAmountWins() {
        Instant t0 = Instant.parse("2026-08-15T09:00:00Z");
        Event a = e("E1", t0, "A", "100.00");
        Event b = e("E2", t0.plus(Duration.ofMinutes(30)), "A", "150.00");
        var res = resolver.resolve(List.of(a, b));
        assertEquals("E2", res.resolvedEvent().getEventId());
        assertEquals(ResolutionMethod.HIGHER_AMOUNT, res.resolutionMethod());
    }

    @Test
    void twoEventsExactlyOneHour_higherAmountWins() {
        Instant t0 = Instant.parse("2026-08-15T09:00:00Z");
        Event a = e("E1", t0, "A", "100.00");
        Event b = e("E2", t0.plus(Duration.ofHours(1)), "A", "150.00");
        var res = resolver.resolve(List.of(a, b));
        assertEquals("E2", res.resolvedEvent().getEventId());
        assertEquals(ResolutionMethod.HIGHER_AMOUNT, res.resolutionMethod());
    }

    @Test
    void twoEventsMoreThanOneHour_latestTimestampWins() {
        Instant t0 = Instant.parse("2026-08-15T09:00:00Z");
        Event a = e("E1", t0, "A", "500.00");
        Event b = e("E2", t0.plus(Duration.ofHours(1)).plus(Duration.ofSeconds(1)), "A", "100.00");
        var res = resolver.resolve(List.of(a, b));
        assertEquals("E2", res.resolvedEvent().getEventId());
        assertEquals(ResolutionMethod.LATEST_TIMESTAMP, res.resolutionMethod());
    }

    @Test
    void sameTimestamp_higherAmountWinsOrTieBreaker() {
        Instant t0 = Instant.parse("2026-08-15T09:00:00Z");
        Event a = e("E1", t0, "A", "100.00");
        Event b = e("E2", t0, "A", "200.00");
        var res = resolver.resolve(List.of(a, b));
        assertEquals("E2", res.resolvedEvent().getEventId());
        assertEquals(ResolutionMethod.HIGHER_AMOUNT, res.resolutionMethod());

        Event c = e("E0", t0, "A", "200.00");
        var res2 = resolver.resolve(List.of(a, b, c));
        // between E1(100), E2(200), E0(200) same timestamp -> lexicographically smallest id wins among equals
        assertEquals("E0", res2.resolvedEvent().getEventId());
        assertEquals(ResolutionMethod.EVENT_ID_TIE_BREAKER, res2.resolutionMethod());
    }

    @Test
    void sameAmountWithinHour_earlierTimestampWins() {
        Instant t0 = Instant.parse("2026-08-15T09:00:00Z");
        Event a = e("E1", t0, "A", "100.00");
        Event b = e("E2", t0.plus(Duration.ofMinutes(30)), "A", "100.00");
        var res = resolver.resolve(List.of(a, b));
        // amounts equal within hour -> earlier timestamp (E1) wins
        assertEquals("E1", res.resolvedEvent().getEventId());
        assertEquals(ResolutionMethod.HIGHER_AMOUNT, res.resolutionMethod());
    }

    @Test
    void threeEvents_mixedGaps_behavesAsPairwiseFold() {
        Instant t0 = Instant.parse("2026-08-15T09:00:00Z");
        Event e1 = e("E1", t0, "A", "100.00");
        Event e2 = e("E2", t0.plus(Duration.ofMinutes(30)), "A", "150.00");
        Event e3 = e("E3", t0.plus(Duration.ofHours(2)), "A", "200.00");
        var res = resolver.resolve(List.of(e1, e2, e3));
        // e1 vs e2 within hour -> e2 (150); e2 vs e3 >1h -> latest timestamp e3
        assertEquals("E3", res.resolvedEvent().getEventId());
        assertEquals(ResolutionMethod.LATEST_TIMESTAMP, res.resolutionMethod());
    }
}
