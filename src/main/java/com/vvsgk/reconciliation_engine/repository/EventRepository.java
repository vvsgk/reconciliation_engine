package com.vvsgk.reconciliation_engine.repository;

import com.vvsgk.reconciliation_engine.entity.Event;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, String> {
    List<Event> findByAccountIdOrderByTimestampAscEventIdAsc(String accountId);
}
