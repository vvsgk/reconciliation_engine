package com.vvsgk.reconciliation_engine.exception;
public class CurrencyMismatchException extends RuntimeException {
    public CurrencyMismatchException(String accountId) { super("Currency does not match account " + accountId); }
}
