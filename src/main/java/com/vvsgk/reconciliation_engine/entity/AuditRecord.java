package com.vvsgk.reconciliation_engine.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "audit_records")
public class AuditRecord {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "reconciliation_id", nullable = false, updatable = false)
    private String reconciliationId;
    @Column(nullable = false) private Instant timestamp;
    @Column(name = "account_id", nullable = false) private String accountId;
    @Lob
    @Column(name = "conflicting_events", nullable = false) private String conflictingEvents;
    @Column(name = "resolved_event", nullable = false) private String resolvedEvent;
    @Column(name = "resolution_method", nullable = false) private String resolutionMethod;
    @Column(name = "final_balance", nullable = false, precision = 19, scale = 2) private BigDecimal finalBalance;

    // Additional audit fields for stronger traceability
    @Column(name = "policy_version") private String policyVersion;
    @Column(name = "previous_resolved_event") private String previousResolvedEvent;
    @Lob
    @Column(name = "decision_reason") private String decisionReason;
    @Column(name = "replay_run_id") private String replayRunId;

    protected AuditRecord() { }
    public AuditRecord(Instant timestamp, String accountId, String conflictingEvents, String resolvedEvent, String resolutionMethod, BigDecimal finalBalance) {
        this.timestamp = timestamp; this.accountId = accountId; this.conflictingEvents = conflictingEvents;
        this.resolvedEvent = resolvedEvent; this.resolutionMethod = resolutionMethod; this.finalBalance = finalBalance;
    }

    // Convenience constructor with extended fields
    public AuditRecord(Instant timestamp, String accountId, String conflictingEvents, String resolvedEvent, String resolutionMethod, BigDecimal finalBalance, String policyVersion, String previousResolvedEvent, String decisionReason, String replayRunId) {
        this(timestamp, accountId, conflictingEvents, resolvedEvent, resolutionMethod, finalBalance);
        this.policyVersion = policyVersion; this.previousResolvedEvent = previousResolvedEvent; this.decisionReason = decisionReason; this.replayRunId = replayRunId;
    }

    public String getReconciliationId() { return reconciliationId; }
    public Instant getTimestamp() { return timestamp; }
    public String getAccountId() { return accountId; }
    public String getConflictingEvents() { return conflictingEvents; }
    public String getResolvedEvent() { return resolvedEvent; }
    public String getResolutionMethod() { return resolutionMethod; }
    public BigDecimal getFinalBalance() { return finalBalance; }
    public String getPolicyVersion() { return policyVersion; }
    public String getPreviousResolvedEvent() { return previousResolvedEvent; }
    public String getDecisionReason() { return decisionReason; }
    public String getReplayRunId() { return replayRunId; }
}
