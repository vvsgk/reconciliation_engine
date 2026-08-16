package com.vvsgk.reconciliation_engine.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "accounts")
public class Account {
    @Id @Column(name = "account_id", nullable = false, updatable = false)
    private String accountId;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;
    @Column(nullable = false)
    private String currency;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private Long version;

    protected Account() { }
    public Account(String accountId, BigDecimal balance, String currency, Instant updatedAt) {
        this.accountId = accountId; this.balance = balance; this.currency = currency; this.updatedAt = updatedAt;
    }
    public void update(BigDecimal balance, Instant updatedAt) { this.balance = balance; this.updatedAt = updatedAt; }
    public String getAccountId() { return accountId; }
    public BigDecimal getBalance() { return balance; }
    public String getCurrency() { return currency; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
