package com.vvsgk.reconciliation_engine.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.vvsgk.reconciliation_engine.dto.EventRequest;
import com.vvsgk.reconciliation_engine.dto.EventResponse;
import com.vvsgk.reconciliation_engine.entity.AuditRecord;
import com.vvsgk.reconciliation_engine.entity.Event;
import com.vvsgk.reconciliation_engine.exception.CurrencyMismatchException;
import com.vvsgk.reconciliation_engine.exception.DuplicateEventException;
import com.vvsgk.reconciliation_engine.reconciliation.ConflictResolver;
import com.vvsgk.reconciliation_engine.reconciliation.ReconciliationResult;
import com.vvsgk.reconciliation_engine.repository.AuditRecordRepository;
import com.vvsgk.reconciliation_engine.repository.EventRepository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final AuditRecordRepository auditRecordRepository;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    private final ConflictResolver resolver = new ConflictResolver();

    public EventService(
            EventRepository eventRepository,
            AuditRecordRepository auditRecordRepository,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate) {

        this.eventRepository = eventRepository;
        this.auditRecordRepository = auditRecordRepository;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public EventResponse processEvent(EventRequest request) {

        Instant now = Instant.now();

        /*
         * The database is the idempotency boundary.  The whole ingestion
         * remains inside one transaction so an event insert, account update,
         * and audit record either all commit or all roll back.
         */

        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO events
                        (
                            event_id,
                            timestamp,
                            account_id,
                            amount,
                            currency,
                            source,
                            created_at,
                            version
                        )
                    VALUES
                        (?, ?, ?, ?, ?, ?, ?, 0)
                    """,
                    request.eventId(),
                    Timestamp.from(request.timestamp()),
                    request.accountId(),
                    request.amount(),
                    request.currency(),
                    request.source(),
                    Timestamp.from(now)
            );
        } catch (DataIntegrityViolationException ex) {
            if (isDuplicateViolation(ex)) {
                throw new DuplicateEventException(request.eventId());
            }
            throw ex;
        }

        /*
         * Create the account row if this is the first event for the account.
         * ON CONFLICT makes concurrent first-event requests safe.  We then
         * lock the row and re-check currency so concurrent currency races
         * cannot leave a mixed-currency account behind.
         */
        jdbcTemplate.update(
                """
                INSERT INTO accounts
                    (
                        account_id,
                        balance,
                        currency,
                        updated_at,
                        version
                    )
                VALUES
                    (?, ?, ?, ?, 0)
                ON CONFLICT (account_id) DO NOTHING
                """,
                request.accountId(),
                request.amount(),
                request.currency(),
                Timestamp.from(now)
        );

        String accountCurrency = jdbcTemplate.queryForObject(
                """
                SELECT currency
                FROM accounts
                WHERE account_id = ?
                FOR UPDATE
                """,
                String.class,
                request.accountId()
        );

        if (!accountCurrency.equals(request.currency())) {
            throw new CurrencyMismatchException(request.accountId());
        }

        /*
         * Re-read after acquiring the account lock.  A concurrent transaction
         * that committed just before this lock was acquired must be included
         * in this reconciliation.
         */
        List<Event> accountEvents =
                eventRepository.findByAccountIdOrderByTimestampAscEventIdAsc(
                        request.accountId()
                );

        ReconciliationResult result = resolver.resolve(accountEvents);

        jdbcTemplate.update(
                """
                UPDATE accounts
                SET balance = ?,
                    updated_at = ?,
                    version = version + 1
                WHERE account_id = ?
                """,
                result.finalBalance(),
                Timestamp.from(now),
                request.accountId()
        );

        String reason = explainDecision(result, accountEvents);

        AuditRecord auditRecord =
                new AuditRecord(
                        now,
                        request.accountId(),
                        asJson(result.consideredEventIds()),
                        result.resolvedEvent().getEventId(),
                        result.resolutionMethod().name(),
                        result.finalBalance(),
                        "1.0",
                        null,
                        reason,
                        null
                );

        auditRecordRepository.save(auditRecord);

        Event persistedEvent = eventRepository.findById(request.eventId())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Inserted event could not be reloaded: " + request.eventId()
                        ));

        return new EventResponse(persistedEvent, result);
    }

    private String explainDecision(
            ReconciliationResult result,
            List<Event> events) {

        StringBuilder sb = new StringBuilder();

        switch (result.resolutionMethod()) {

            case HIGHER_AMOUNT -> {

                sb.append("HIGHER_AMOUNT selected ")
                        .append(
                                result.resolvedEvent().getEventId()
                        )
                        .append(" because:\n");

                for (Event event : events) {

                    sb.append(event.getEventId())
                            .append(" amount = ")
                            .append(event.getAmount())
                            .append("\n");
                }
            }

            case LATEST_TIMESTAMP -> {

                sb.append("LATEST_TIMESTAMP selected ")
                        .append(
                                result.resolvedEvent().getEventId()
                        )
                        .append(" because:\n");

                for (Event event : events) {

                    sb.append(event.getEventId())
                            .append(" = ")
                            .append(event.getTimestamp())
                            .append("\n");
                }
            }

            case EVENT_ID_TIE_BREAKER -> {

                sb.append("EVENT_ID_TIE_BREAKER selected ")
                        .append(
                                result.resolvedEvent().getEventId()
                        )
                        .append(
                                " because: exact timestamp+amount tie "
                                        + "and lexicographically smallest eventId wins."
                        );
            }

            default -> sb.append("INITIAL_EVENT");
        }

        return sb.toString();
    }

    private String asJson(Object value) {

        try {

            return objectMapper.writeValueAsString(value);

        } catch (JacksonException ex) {

            throw new IllegalStateException(
                    "Unable to serialize audit event IDs",
                    ex
            );
        }
    }

    private boolean isDuplicateViolation(Throwable ex) {

        if (ex == null) {
            return false;
        }

        /*
         * PostgreSQL duplicate-key SQLSTATE.
         */
        if (ex instanceof java.sql.SQLException sqlException) {

            if ("23505".equals(sqlException.getSQLState())) {
                return true;
            }
        }

        String message = ex.getMessage();

        if (message != null) {

            String lowerMessage =
                    message.toLowerCase();

            if (lowerMessage.contains("duplicate")
                    || lowerMessage.contains("unique")
                    || lowerMessage.contains("event_id")) {

                return true;
            }
        }

        Throwable cause = ex.getCause();

        if (cause != null) {
            return isDuplicateViolation(cause);
        }

        return false;
    }
}