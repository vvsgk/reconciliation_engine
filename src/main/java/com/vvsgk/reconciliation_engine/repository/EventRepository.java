package com.vvsgk.reconciliation_engine.repository;

import com.vvsgk.reconciliation_engine.entity.Event;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EventRepository extends JpaRepository<Event, String> {

    List<Event> findByAccountIdOrderByTimestampAscEventIdAsc(String accountId);

    @Query(value = "SELECT 1 FROM events WHERE event_id = ?1", nativeQuery = true)
    Optional<Integer> findEventIdExists(String eventId);
}