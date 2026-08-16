package com.vvsgk.reconciliation_engine;

import com.vvsgk.reconciliation_engine.dto.EventRequest;
import com.vvsgk.reconciliation_engine.dto.ReplayRequest;
import com.vvsgk.reconciliation_engine.dto.ReplayResponse;
import com.vvsgk.reconciliation_engine.service.EventService;
import com.vvsgk.reconciliation_engine.service.ReplayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PRD requirement: same events + same configuration must produce the same final
 * state, the same audit decisions, and the same replay fingerprint — regardless
 * of arrival order and however many times replay is executed.
 */
public class ReplayDeterminismTests extends PostgresTestBase {

    @Autowired EventService eventService;
    @Autowired ReplayService replayService;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final String ACCOUNT = "ACC-DETERMINISM";

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("DELETE FROM audit_records");
        jdbcTemplate.execute("DELETE FROM events");
        jdbcTemplate.execute("DELETE FROM accounts");
    }

    private List<EventRequest> scenario() {
        // Mix of within-hour conflicts (amount rule), >1h gaps (timestamp rule) and a tie.
        List<EventRequest> events = new ArrayList<>();
        events.add(event("DET-001", "2026-08-15T09:00:00Z", "150.00"));
        events.add(event("DET-002", "2026-08-15T09:30:00Z", "175.00")); // within 1h, higher amount
        events.add(event("DET-003", "2026-08-15T09:45:00Z", "120.00")); // within 1h, lower amount
        events.add(event("DET-004", "2026-08-15T12:00:00Z", "110.00")); // >1h later, latest timestamp
        events.add(event("DET-005", "2026-08-15T12:00:00Z", "110.00")); // exact tie -> eventId tie-breaker
        return events;
    }

    private EventRequest event(String id, String timestamp, String amount) {
        return new EventRequest(id, Instant.parse(timestamp), ACCOUNT, new BigDecimal(amount), "USD", "test");
    }

    @Test
    void replayTwiceProducesIdenticalStateFingerprintAndDecisions() {
        for (EventRequest event : scenario()) eventService.processEvent(event);

        ReplayResponse first = replayService.replay(new ReplayRequest(ACCOUNT, null));
        ReplayResponse second = replayService.replay(new ReplayRequest(ACCOUNT, null));

        // final state #1 == final state #2
        assertEquals(first.reconciliation().resolvedEvent().getEventId(),
                second.reconciliation().resolvedEvent().getEventId());
        assertEquals(first.reconciliation().finalBalance(), second.reconciliation().finalBalance());
        // resolution decisions #1 == #2
        assertEquals(first.reconciliation().resolutionMethod(), second.reconciliation().resolutionMethod());
        assertEquals(first.reconciliation().consideredEventIds(), second.reconciliation().consideredEventIds());
        assertEquals(first.normalizedOrdering(), second.normalizedOrdering());
        // fingerprint #1 == fingerprint #2
        assertEquals(first.stateHash(), second.stateHash());
        // each replay run remains individually traceable
        assertNotNull(first.replayRunId());
        assertNotEquals(first.replayRunId(), second.replayRunId());
    }

    @Test
    void differentArrivalOrderProducesIdenticalFinalStateAndFingerprint() {
        for (EventRequest event : scenario()) eventService.processEvent(event);
        ReplayResponse ordered = replayService.replay(new ReplayRequest(ACCOUNT, null));

        cleanDatabase();

        List<EventRequest> shuffled = new ArrayList<>(scenario());
        Collections.reverse(shuffled); // deterministic permutation: worst-case out-of-order arrival
        for (EventRequest event : shuffled) eventService.processEvent(event);
        ReplayResponse reversed = replayService.replay(new ReplayRequest(ACCOUNT, null));

        assertEquals(ordered.reconciliation().resolvedEvent().getEventId(),
                reversed.reconciliation().resolvedEvent().getEventId());
        assertEquals(ordered.reconciliation().resolutionMethod(), reversed.reconciliation().resolutionMethod());
        assertEquals(ordered.reconciliation().finalBalance(), reversed.reconciliation().finalBalance());
        assertEquals(ordered.normalizedOrdering(), reversed.normalizedOrdering());
        assertEquals(ordered.stateHash(), reversed.stateHash());
    }

    @Test
    void replayUntilBoundaryIsDeterministic() {
        for (EventRequest event : scenario()) eventService.processEvent(event);
        Instant until = Instant.parse("2026-08-15T09:45:00Z");

        ReplayResponse first = replayService.replay(new ReplayRequest(ACCOUNT, until));
        ReplayResponse second = replayService.replay(new ReplayRequest(ACCOUNT, until));

        assertEquals("DET-002", first.reconciliation().resolvedEvent().getEventId());
        assertEquals(List.of("DET-001", "DET-002", "DET-003"), first.normalizedOrdering());
        assertEquals(first.stateHash(), second.stateHash());
        assertEquals(first.reconciliation().finalBalance(), second.reconciliation().finalBalance());
    }
}
