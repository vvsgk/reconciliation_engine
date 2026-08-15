package com.vvsgk.reconciliation_engine.exception;
public class DuplicateEventException extends RuntimeException {
    public DuplicateEventException(String eventId) { super("Event already exists: " + eventId); }
}
