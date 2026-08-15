package com.vvsgk.reconciliation_engine.reconciliation;

import com.vvsgk.reconciliation_engine.entity.Event;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class ConflictResolver {
    private static final Duration CONFLICT_WINDOW = Duration.ofHours(1);

    /**
     * Resolve according to PRD rules applied pairwise across the chronological
     * sequence of events. Start with the earliest event and fold in each next
     * event comparing the current candidate with the new event using the precise
     * rule:
     *
     * - If timestamp difference <= 1 hour -> choose the event with the higher amount
     *   (tie-breaker: earlier timestamp, then lexicographically smaller eventId)
     * - If timestamp difference > 1 hour -> choose the latest timestamp
     *   (tie-breaker: higher amount, then lexicographically smaller eventId)
     */
    public ReconciliationResult resolve(List<Event> events) {
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException("At least one event is required");
        }

        List<Event> ordered = events.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Event::getTimestamp).thenComparing(Event::getEventId))
                .toList();

        Event candidate = ordered.get(0);
        ResolutionMethod method = ResolutionMethod.INITIAL_EVENT;

        for (int i = 1; i < ordered.size(); i++) {
            Event next = ordered.get(i);
            Duration diff = Duration.between(candidate.getTimestamp(), next.getTimestamp());
            if (diff.compareTo(CONFLICT_WINDOW) <= 0) {
                // Within 1 hour -> higher amount wins
                int amtCmp = candidate.getAmount().compareTo(next.getAmount());
                if (amtCmp < 0) {
                    candidate = next;
                    method = ResolutionMethod.HIGHER_AMOUNT;
                } else if (amtCmp > 0) {
                    method = ResolutionMethod.HIGHER_AMOUNT;
                } else {
                    // same amount -> earlier timestamp wins
                    if (!candidate.getTimestamp().equals(next.getTimestamp())) {
                        if (candidate.getTimestamp().isBefore(next.getTimestamp())) {
                            method = ResolutionMethod.HIGHER_AMOUNT;
                        } else {
                            candidate = next;
                            method = ResolutionMethod.HIGHER_AMOUNT;
                        }
                    } else {
                        // same timestamp and amount -> lexicographically smaller eventId wins
                        int idCmp = candidate.getEventId().compareTo(next.getEventId());
                        if (idCmp <= 0) {
                            method = ResolutionMethod.EVENT_ID_TIE_BREAKER;
                        } else {
                            candidate = next;
                            method = ResolutionMethod.EVENT_ID_TIE_BREAKER;
                        }
                    }
                }
            } else {
                // More than 1 hour apart -> latest timestamp wins (next is later)
                // If timestamps equal (edge-case), prefer higher amount then eventId
                if (!candidate.getTimestamp().equals(next.getTimestamp())) {
                    candidate = next;
                    method = ResolutionMethod.LATEST_TIMESTAMP;
                } else {
                    int amtCmp = candidate.getAmount().compareTo(next.getAmount());
                    if (amtCmp < 0) {
                        candidate = next;
                        method = ResolutionMethod.LATEST_TIMESTAMP;
                    } else if (amtCmp > 0) {
                        method = ResolutionMethod.LATEST_TIMESTAMP;
                    } else {
                        int idCmp = candidate.getEventId().compareTo(next.getEventId());
                        if (idCmp <= 0) {
                            method = ResolutionMethod.EVENT_ID_TIE_BREAKER;
                        } else {
                            candidate = next;
                            method = ResolutionMethod.EVENT_ID_TIE_BREAKER;
                        }
                    }
                }
            }
        }

        return new ReconciliationResult(
                candidate,
                method,
                ordered.stream().map(Event::getEventId).toList(),
                candidate.getAmount()
        );
    }

    // keep old methods removed - resolution is now implemented above

}

