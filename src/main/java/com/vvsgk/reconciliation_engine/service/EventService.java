package com.vvsgk.reconciliation_engine.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.vvsgk.reconciliation_engine.dto.EventRequest;
import com.vvsgk.reconciliation_engine.dto.EventResponse;
import com.vvsgk.reconciliation_engine.entity.Account;
import com.vvsgk.reconciliation_engine.entity.AuditRecord;
import com.vvsgk.reconciliation_engine.entity.Event;
import com.vvsgk.reconciliation_engine.exception.CurrencyMismatchException;
import com.vvsgk.reconciliation_engine.exception.DuplicateEventException;
import com.vvsgk.reconciliation_engine.reconciliation.ConflictResolver;
import com.vvsgk.reconciliation_engine.reconciliation.ReconciliationResult;
import com.vvsgk.reconciliation_engine.repository.AccountRepository;
import com.vvsgk.reconciliation_engine.repository.AuditRecordRepository;
import com.vvsgk.reconciliation_engine.repository.EventRepository;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {
    private final EventRepository eventRepository;
    private final AccountRepository accountRepository;
    private final AuditRecordRepository auditRecordRepository;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ConflictResolver resolver = new ConflictResolver();

    public EventService(EventRepository eventRepository, AccountRepository accountRepository,
                        AuditRecordRepository auditRecordRepository, ObjectMapper objectMapper, JdbcTemplate jdbcTemplate) {
        this.eventRepository = eventRepository; this.accountRepository = accountRepository;
        this.auditRecordRepository = auditRecordRepository; this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public EventResponse processEvent(EventRequest request) {
        // Outer method implements retry loop. Each attempt runs in its own transaction.
        final int MAX_ATTEMPTS = 3;
        int attempts = 0;
        while (true) {
            attempts++;
            try {
                return processEventTransactionally(request);
            } catch (org.springframework.dao.OptimisticLockingFailureException ex) {
                if (attempts >= MAX_ATTEMPTS) throw ex;
                // retry in a new transaction
            }
        }
    }

    @Transactional
    protected EventResponse processEventTransactionally(EventRequest request) {
        Instant now = Instant.now();
        Event event = new Event(request.eventId(), request.timestamp(), request.accountId(), request.amount(),
                request.currency(), request.source(), now);

        // Validate currency before persisting event so the whole flow (validation + insert + account update + audit)
        // happens within a single transaction and is rolled back on error.
        Account account = accountRepository.findById(request.accountId()).orElse(null);
        if (account != null && !account.getCurrency().equals(request.currency())) throw new CurrencyMismatchException(request.accountId());

        // Check for duplicate event ID before trying to insert
        if (eventRepository.findEventIdExists(request.eventId()).isPresent()) {
            throw new DuplicateEventException(request.eventId());
        }

        try {
            eventRepository.saveAndFlush(event);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            // Check if this is a duplicate-key violation (race condition between check and insert)
            String message = ex.getMessage();
            Throwable cause = ex.getCause();
            
            boolean isDuplicate = false;
            if (message != null && (message.contains("unique") || message.contains("duplicate") || message.contains("event_id"))) {
                isDuplicate = true;
            }
            if (!isDuplicate && cause != null) {
                String causeMessage = cause.getMessage();
                if (causeMessage != null && (causeMessage.contains("unique") || causeMessage.contains("duplicate") || causeMessage.contains("event_id"))) {
                    isDuplicate = true;
                }
            }
            
            if (isDuplicate) {
                throw new DuplicateEventException(request.eventId());
            }
            throw ex;
        }

        ReconciliationResult result = resolver.resolve(eventRepository.findByAccountIdOrderByTimestampAscEventIdAsc(request.accountId()));

        // Use JDBC upsert pattern to reduce optimistic-lock contention on account row
        if (account == null) {
            try {
                int updated = jdbcTemplate.update("UPDATE accounts SET balance=?, currency=?, updated_at=? WHERE account_id=?",
                        result.finalBalance(), request.currency(), java.sql.Timestamp.from(now), request.accountId());
                if (updated == 0) {
                    try {
                        jdbcTemplate.update("INSERT INTO accounts (account_id, balance, currency, updated_at) VALUES (?,?,?,?)",
                                request.accountId(), result.finalBalance(), request.currency(), java.sql.Timestamp.from(now));
                    } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                        jdbcTemplate.update("UPDATE accounts SET balance=?, currency=?, updated_at=? WHERE account_id=?",
                                result.finalBalance(), request.currency(), java.sql.Timestamp.from(now), request.accountId());
                    }
                }
            } catch (org.springframework.dao.DataAccessException ex) {
                throw ex;
            }
        } else {
            jdbcTemplate.update("UPDATE accounts SET balance=?, updated_at=? WHERE account_id=?",
                    result.finalBalance(), java.sql.Timestamp.from(now), request.accountId());
        }

        String reason = explainDecision(result, eventRepository.findByAccountIdOrderByTimestampAscEventIdAsc(request.accountId()));
        auditRecordRepository.save(new AuditRecord(now, request.accountId(), asJson(result.consideredEventIds()),
                result.resolvedEvent().getEventId(), result.resolutionMethod().name(), result.finalBalance(),
                "1.0", null, reason, null));

        return new EventResponse(event, result);
    }

    private String explainDecision(ReconciliationResult result, java.util.List<Event> events) {
        StringBuilder sb = new StringBuilder();
        switch (result.resolutionMethod()) {
            case HIGHER_AMOUNT -> {
                sb.append("HIGHER_AMOUNT selected ").append(result.resolvedEvent().getEventId()).append(" because:\n");
                for (Event e : events) sb.append(e.getEventId()).append(" amount = ").append(e.getAmount()).append("\n");
            }
            case LATEST_TIMESTAMP -> {
                sb.append("LATEST_TIMESTAMP selected ").append(result.resolvedEvent().getEventId()).append(" because:\n");
                for (Event e : events) sb.append(e.getEventId()).append(" = ").append(e.getTimestamp()).append("\n");
            }
            case EVENT_ID_TIE_BREAKER -> sb.append("EVENT_ID_TIE_BREAKER selected ").append(result.resolvedEvent().getEventId()).append(" because: exact timestamp+amount tie and lexicographically smallest eventId wins.");
            default -> sb.append("INITIAL_EVENT");
        }
        return sb.toString();
    }

    private String asJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException ex) { throw new IllegalStateException("Unable to serialize audit event IDs", ex); }
    }
}
