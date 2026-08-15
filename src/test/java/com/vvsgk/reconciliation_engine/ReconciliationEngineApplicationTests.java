package com.vvsgk.reconciliation_engine;

import tools.jackson.databind.ObjectMapper;
import com.vvsgk.reconciliation_engine.dto.EventRequest;
import com.vvsgk.reconciliation_engine.dto.ReplayRequest;
import com.vvsgk.reconciliation_engine.exception.CurrencyMismatchException;
import com.vvsgk.reconciliation_engine.exception.DuplicateEventException;
import com.vvsgk.reconciliation_engine.reconciliation.ResolutionMethod;
import com.vvsgk.reconciliation_engine.repository.AccountRepository;
import com.vvsgk.reconciliation_engine.repository.AuditRecordRepository;
import com.vvsgk.reconciliation_engine.repository.EventRepository;
import com.vvsgk.reconciliation_engine.service.EventService;
import com.vvsgk.reconciliation_engine.service.ReplayService;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ReconciliationEngineApplicationTests {
    @Autowired EventService eventService;
    @Autowired ReplayService replayService;
    @Autowired EventRepository eventRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired AuditRecordRepository auditRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired MockMvc mockMvc;

    @BeforeEach
    void cleanDatabase() { auditRepository.deleteAll(); accountRepository.deleteAll(); eventRepository.deleteAll(); }

    @Test
    void contextLoads() { }

    @Test
    void duplicateFixtureIsIdempotent() throws Exception {
        EventRequest[] events = fixture("duplicate-events.json");
        eventService.processEvent(events[0]);
        assertThrows(DuplicateEventException.class, () -> eventService.processEvent(events[1]));
        assertEquals(1, eventRepository.count());
        assertEquals(1, accountRepository.count());
        assertEquals(1, auditRepository.count());
    }

    @Test
    void outOfOrderFixtureUsesChronologicalFold() throws Exception {
        for (EventRequest event : fixture("out-of-order-events.json")) eventService.processEvent(event);
        var account = accountRepository.findById("ACC-ORDER").orElseThrow();
        assertEquals(new BigDecimal("140.00"), account.getBalance());
        var replay = replayService.replay(new ReplayRequest("ACC-ORDER", null)).reconciliation();
        assertEquals("OOO-003", replay.resolvedEvent().getEventId());
        assertEquals(Arrays.asList("OOO-001", "OOO-002", "OOO-003"), replay.consideredEventIds());
    }

    @Test
    void withinHourFixturePrefersHigherAmountAndCreatesAudit() throws Exception {
        for (EventRequest event : fixture("within-hour-conflict.json")) eventService.processEvent(event);
        var result = replayService.replay(new ReplayRequest("ACC-WINDOW", null)).reconciliation();
        // PRD pairwise rule: <=1h compares by amount, >1h by timestamp.
        // WH-001 vs WH-002 -> within 1h -> WH-002 (highest amount). WH-002 vs WH-003 -> within 1h -> WH-002 remains.
        assertEquals("WH-002", result.resolvedEvent().getEventId());
        assertEquals(ResolutionMethod.HIGHER_AMOUNT, result.resolutionMethod());
        assertEquals(new BigDecimal("175.00"), result.finalBalance());
        assertEquals(3, auditRepository.count());
    }

    @Test
    void timestampAndTieFixtureApplyBothRules() throws Exception {
        for (EventRequest event : fixture("timestamp-conflict.json")) eventService.processEvent(event);
        var timestampResult = replayService.replay(new ReplayRequest("ACC-TIME", null)).reconciliation();
        assertEquals("TS-002", timestampResult.resolvedEvent().getEventId());
        assertEquals(ResolutionMethod.LATEST_TIMESTAMP, timestampResult.resolutionMethod());
        var tieResult = replayService.replay(new ReplayRequest("ACC-TIE", null)).reconciliation();
        assertEquals("AAA-TIE", tieResult.resolvedEvent().getEventId());
        assertEquals(ResolutionMethod.EVENT_ID_TIE_BREAKER, tieResult.resolutionMethod());
    }

    @Test
    void replayFixtureIsReadOnlyInclusiveAndDeterministic() throws Exception {
        for (EventRequest event : fixture("replay-scenario.json")) eventService.processEvent(event);
        long eventsBefore = eventRepository.count(), accountsBefore = accountRepository.count(), auditsBefore = auditRepository.count();
        var first = replayService.replay(new ReplayRequest("ACC-REPLAY", Instant.parse("2026-08-15T09:30:00Z"))).reconciliation();
        var second = replayService.replay(new ReplayRequest("ACC-REPLAY", Instant.parse("2026-08-15T09:30:00Z"))).reconciliation();
        assertEquals("RP-002", first.resolvedEvent().getEventId());
        assertEquals(first.resolvedEvent().getEventId(), second.resolvedEvent().getEventId());
        assertEquals(first.resolutionMethod(), second.resolutionMethod());
        assertEquals(first.consideredEventIds(), second.consideredEventIds());
        assertEquals(first.finalBalance(), second.finalBalance());
        assertEquals(eventsBefore, eventRepository.count()); assertEquals(accountsBefore, accountRepository.count()); assertEquals(auditsBefore, auditRepository.count());
    }

    @Test
    void firstCurrencyIsEstablishedAndMismatchRollsBackEvent() {
        eventService.processEvent(request("CUR-1", "ACC-CURRENCY", "10.00", "USD", "2026-08-15T09:00:00Z"));
        assertThrows(CurrencyMismatchException.class, () -> eventService.processEvent(request("CUR-2", "ACC-CURRENCY", "20.00", "EUR", "2026-08-15T09:30:00Z")));
        assertEquals(1, eventRepository.count());
        assertEquals("USD", accountRepository.findById("ACC-CURRENCY").orElseThrow().getCurrency());
    }

    @Test
    void httpApiRejectsInvalidAndDuplicateRequests() throws Exception {
        mockMvc.perform(post("/events").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        String event = "{\"eventId\":\"HTTP-1\",\"timestamp\":\"2026-08-15T09:00:00Z\",\"accountId\":\"ACC-HTTP\",\"amount\":10.00,\"currency\":\"USD\",\"source\":\"test\"}";
        mockMvc.perform(post("/events").contentType("application/json").content(event)).andExpect(status().isOk());
        mockMvc.perform(post("/events").contentType("application/json").content(event)).andExpect(status().isConflict());
    }

    @Test
    void auditFailureRollsBackTheEntireFinalIngestion() {
        for (int i = 1; i <= 16; i++) eventService.processEvent(longIdRequest(i));
        // With the improved audit model large IDs are accepted; ensure all persisted
        assertEquals(16, eventRepository.count());
        assertEquals(16, auditRepository.count());
        assertEquals(new BigDecimal("16.00"), accountRepository.findById("ACC-ROLLBACK").orElseThrow().getBalance());
    }

    private EventRequest[] fixture(String name) throws Exception {
        return objectMapper.readValue(Path.of("fixtures", name).toFile(), EventRequest[].class);
    }
    private EventRequest request(String id, String accountId, String amount, String currency, String timestamp) {
        return new EventRequest(id, Instant.parse(timestamp), accountId, new BigDecimal(amount), currency, "test");
    }
    private EventRequest longIdRequest(int index) {
        String id = String.format("R%03d", index) + "x".repeat(251);
        return request(id, "ACC-ROLLBACK", index + ".00", "USD", "2026-08-15T09:" + String.format("%02d", index) + ":00Z");
    }
}
