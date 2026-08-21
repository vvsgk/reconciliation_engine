package com.vvsgk.reconciliation_engine.service;

import com.vvsgk.reconciliation_engine.dto.ReplayRequest;
import com.vvsgk.reconciliation_engine.dto.ReplayResponse;
import com.vvsgk.reconciliation_engine.entity.Event;
import com.vvsgk.reconciliation_engine.reconciliation.ConflictResolver;
import com.vvsgk.reconciliation_engine.reconciliation.ReconciliationResult;
import com.vvsgk.reconciliation_engine.repository.EventRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReplayService {

    private final EventRepository eventRepository;
    private final ConflictResolver resolver = new ConflictResolver();

    public ReplayService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Transactional(readOnly = true)
    public ReplayResponse replay(ReplayRequest request) {

        List<Event> events =
                eventRepository
                        .findByAccountIdOrderByTimestampAscEventIdAsc(
                                request.accountId()
                        )
                        .stream()
                        .filter(event ->
                                request.until() == null
                                        || !event.getTimestamp()
                                        .isAfter(request.until())
                        )
                        .toList();

        ReconciliationResult result =
                resolver.resolve(events);

        List<String> ordering =
                events.stream()
                        .map(Event::getEventId)
                        .collect(Collectors.toList());

        String hash =
                deterministicHash(
                        ordering,
                        result
                );

        String replayRunId =
                UUID.randomUUID().toString();

        return new ReplayResponse(
                result,
                ordering,
                hash,
                replayRunId
        );
    }

    private String deterministicHash(
            List<String> ordering,
            ReconciliationResult result) {

        try {
            MessageDigest md =
                    MessageDigest.getInstance("SHA-256");

            StringBuilder sb =
                    new StringBuilder();

            for (String id : ordering) {
                sb.append(id).append("|");
            }

            sb.append("resolved:")
                    .append(result.resolvedEvent().getEventId())
                    .append("|");

            sb.append("method:")
                    .append(result.resolutionMethod())
                    .append("|");

            md.update(
                    sb.toString()
                            .getBytes(StandardCharsets.UTF_8)
            );

            byte[] digest = md.digest();

            StringBuilder hex =
                    new StringBuilder();

            for (byte b : digest) {
                hex.append(
                        String.format("%02x", b)
                );
            }

            return hex.toString();

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 algorithm is not available",
                    e
            );
        }
    }
}