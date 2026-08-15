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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
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

    public EventService(
            EventRepository eventRepository,
            AccountRepository accountRepository,
            AuditRecordRepository auditRecordRepository,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate) {

        this.eventRepository = eventRepository;
        this.accountRepository = accountRepository;
        this.auditRecordRepository = auditRecordRepository;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public EventResponse processEvent(EventRequest request) {

        final int MAX_ATTEMPTS = 3;

        int attempts = 0;

        while (true) {

            attempts++;

            try {

                return processEventTransactionally(request);

            } catch (OptimisticLockingFailureException ex) {

                if (attempts >= MAX_ATTEMPTS) {
                    throw ex;
                }
            }
        }
    }

    @Transactional
    protected EventResponse processEventTransactionally(EventRequest request) {

        Instant now = Instant.now();

        /*
         * ------------------------------------------------------------
         * 1. Validate account currency BEFORE inserting the event
         * ------------------------------------------------------------
         */

        Account account =
                accountRepository
                        .findById(request.accountId())
                        .orElse(null);

        if (account != null
                && !account.getCurrency().equals(request.currency())) {

            throw new CurrencyMismatchException(request.accountId());
        }

        /*
         * ------------------------------------------------------------
         * 2. Build event
         * ------------------------------------------------------------
         */

        Event event = new Event(
                request.eventId(),
                request.timestamp(),
                request.accountId(),
                request.amount(),
                request.currency(),
                request.source(),
                now
        );

        /*
         * ------------------------------------------------------------
         * 3. EXPLICIT DATABASE INSERT
         * ------------------------------------------------------------
         *
         * DO NOT use eventRepository.saveAndFlush(event) here.
         *
         * eventId is an application-assigned String @Id.
         *
         * Using JdbcTemplate forces a real SQL INSERT.
         *
         * Therefore:
         *
         * first event:
         *     INSERT succeeds
         *
         * duplicate event:
         *     PRIMARY KEY violation
         *
         * concurrent duplicate:
         *     one INSERT succeeds
         *     remaining INSERTs receive duplicate-key errors
         *
         * This makes the database the authoritative idempotency boundary.
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
                    event.getEventId(),
                    Timestamp.from(event.getTimestamp()),
                    event.getAccountId(),
                    event.getAmount(),
                    event.getCurrency(),
                    event.getSource(),
                    Timestamp.from(event.getCreatedAt())
            );

        } catch (DataIntegrityViolationException ex) {

            if (isDuplicateViolation(ex)) {

                throw new DuplicateEventException(
                        request.eventId()
                );
            }

            throw ex;
        }

        /*
         * ------------------------------------------------------------
         * 4. Reconciliation
         * ------------------------------------------------------------
         */

        List<Event> accountEvents =
                eventRepository
                        .findByAccountIdOrderByTimestampAscEventIdAsc(
                                request.accountId()
                        );

        ReconciliationResult result =
                resolver.resolve(accountEvents);

        /*
         * ------------------------------------------------------------
         * 5. Update account state
         * ------------------------------------------------------------
         */

        if (account == null) {

            try {

                int updated =
                        jdbcTemplate.update(
                                """
                                UPDATE accounts
                                SET balance = ?,
                                    currency = ?,
                                    updated_at = ?,
                                    version = version + 1
                                WHERE account_id = ?
                                """,
                                result.finalBalance(),
                                request.currency(),
                                Timestamp.from(now),
                                request.accountId()
                        );

                if (updated == 0) {

                    try {

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
                                """,
                                request.accountId(),
                                result.finalBalance(),
                                request.currency(),
                                Timestamp.from(now)
                        );

                    } catch (DataIntegrityViolationException ex) {

                        /*
                         * Another request created this account between
                         * our UPDATE and INSERT.
                         */
                        jdbcTemplate.update(
                                """
                                UPDATE accounts
                                SET balance = ?,
                                    currency = ?,
                                    updated_at = ?,
                                    version = version + 1
                                WHERE account_id = ?
                                """,
                                result.finalBalance(),
                                request.currency(),
                                Timestamp.from(now),
                                request.accountId()
                        );
                    }
                }

            } catch (DataAccessException ex) {

                throw ex;
            }

        } else {

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
        }

        /*
         * ------------------------------------------------------------
         * 6. Audit
         * ------------------------------------------------------------
         */

        String reason =
                explainDecision(
                        result,
                        accountEvents
                );

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

        /*
         * ------------------------------------------------------------
         * 7. Response
         * ------------------------------------------------------------
         */

        return new EventResponse(
                event,
                result
        );
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