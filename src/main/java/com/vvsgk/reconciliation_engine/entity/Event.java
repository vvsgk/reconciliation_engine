package com.vvsgk.reconciliation_engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "events",
        indexes = {
                @Index(
                        name = "idx_account_timestamp",
                        columnList = "account_id, timestamp, event_id"
                )
        }
)
public class Event {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private String source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /*
     * Important:
     *
     * A newly-created Event has version == null.
     * Spring Data JPA therefore treats it as a new entity and uses INSERT
     * instead of merge().
     *
     * The event_id primary key then provides the atomic idempotency
     * guarantee under concurrent requests.
     */
    @Version
    private Long version;

    protected Event() {
    }

    public Event(
            String eventId,
            Instant timestamp,
            String accountId,
            BigDecimal amount,
            String currency,
            String source,
            Instant createdAt
    ) {
        this.eventId = eventId;
        this.timestamp = timestamp;
        this.accountId = accountId;
        this.amount = amount;
        this.currency = currency;
        this.source = source;
        this.createdAt = createdAt;
    }

    public String getEventId() {
        return eventId;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getAccountId() {
        return accountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getSource() {
        return source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}