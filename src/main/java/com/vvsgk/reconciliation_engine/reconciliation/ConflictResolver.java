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
     * Resolve a list of events by first grouping events that are connected through
     * the conflict window (adjacent events no more than CONFLICT_WINDOW apart).
     * Each group is resolved independently according to a deterministic policy:
     *
     * - If a group's span (maxTimestamp - minTimestamp) <= CONFLICT_WINDOW:
     *     -> choose the event with the highest amount (tie-breaker: earlier timestamp, then lexicographically smaller eventId)
     *     -> resolution method: HIGHER_AMOUNT or EVENT_ID_TIE_BREAKER when tie on amount+timestamp
     *
     * - If a group's span > CONFLICT_WINDOW:
     *     -> choose the event with the latest timestamp (tie-breaker: higher amount, then lexicographically smaller eventId)
     *     -> resolution method: LATEST_TIMESTAMP or EVENT_ID_TIE_BREAKER when exact timestamp+amount ties
     *
     * The final resolved event is the resolved event of the last chronological group
     * (because groups are separated by gaps > CONFLICT_WINDOW and newer timestamp wins across groups).
     */
    public ReconciliationResult resolve(List<Event> events) {
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException("At least one event is required");
        }

        List<Event> ordered = events.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Event::getTimestamp).thenComparing(Event::getEventId))
                .toList();

        // Build conflict groups by chaining adjacent events where gap <= CONFLICT_WINDOW
        List<List<Event>> groups = new ArrayList<>();
        List<Event> current = new ArrayList<>();
        current.add(ordered.get(0));

        for (int i = 1; i < ordered.size(); i++) {
            Event prev = ordered.get(i - 1);
            Event cur = ordered.get(i);
            Duration gap = Duration.between(prev.getTimestamp(), cur.getTimestamp());
            if (gap.compareTo(CONFLICT_WINDOW) <= 0) {
                current.add(cur);
            } else {
                groups.add(current);
                current = new ArrayList<>();
                current.add(cur);
            }
        }
        groups.add(current);

        // Resolve each group independently
        Event finalCandidate = null;
        ResolutionMethod finalMethod = ResolutionMethod.INITIAL_EVENT;

        for (List<Event> group : groups) {
            Event groupWinner = resolveGroup(group);
            ResolutionMethod groupMethod = deriveMethodForGroup(group, groupWinner);

            // Newer group's winner supersedes previous group's winner.
            finalCandidate = groupWinner;
            finalMethod = groupMethod;
        }

        // If there are multiple groups, the cross-group resolution is by timestamp
        // (LATEST_TIMESTAMP) per PRD. If only a single group exists, preserve
        // the group's resolution method (e.g., HIGHER_AMOUNT).
        if (groups.size() > 1) {
            finalMethod = ResolutionMethod.LATEST_TIMESTAMP;
        }

        return new ReconciliationResult(
                finalCandidate,
                finalMethod,
                ordered.stream().map(Event::getEventId).toList(),
                finalCandidate.getAmount()
        );
    }

    private Event resolveGroup(List<Event> group) {
        if (group.size() == 1) return group.get(0);

        // Per PRD: events that are connected (adjacent gap <= CONFLICT_WINDOW)
        // form a conflict group. Within that group, the HIGHER_AMOUNT policy
        // applies. Tie-breakers: earlier timestamp, then lexicographically
        // smaller eventId to ensure determinism.
        Comparator<Event> cmp = (a, b) -> {
            int amtCmp = a.getAmount().compareTo(b.getAmount());
            if (amtCmp != 0) return amtCmp > 0 ? 1 : -1; // prefer higher amount
            if (!a.getTimestamp().equals(b.getTimestamp()))
                return a.getTimestamp().isBefore(b.getTimestamp()) ? 1 : -1; // prefer earlier timestamp
            int idCmp = a.getEventId().compareTo(b.getEventId());
            if (idCmp != 0) return idCmp < 0 ? 1 : -1; // prefer lexicographically smaller eventId
            return 0;
        };
        // Use max with comparator that returns positive when first arg is preferred
        return group.stream().max(cmp).orElse(group.get(group.size() - 1));
    }

    private ResolutionMethod deriveMethodForGroup(List<Event> group, Event winner) {
        if (group.size() == 1) return ResolutionMethod.INITIAL_EVENT;

        // Within a conflict group we apply the HIGHER_AMOUNT policy. If multiple
        // events share exact amount+timestamp, use EVENT_ID_TIE_BREAKER.
        long sameAmountAndTimestamp = group.stream()
                .filter(e -> e.getAmount().compareTo(winner.getAmount()) == 0 && e.getTimestamp().equals(winner.getTimestamp()))
                .count();
        if (sameAmountAndTimestamp > 1) return ResolutionMethod.EVENT_ID_TIE_BREAKER;
        return ResolutionMethod.HIGHER_AMOUNT;
    }
}

