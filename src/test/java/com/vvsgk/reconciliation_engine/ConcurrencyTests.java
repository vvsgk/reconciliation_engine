package com.vvsgk.reconciliation_engine;

import com.vvsgk.reconciliation_engine.dto.EventRequest;
import com.vvsgk.reconciliation_engine.service.EventService;
import com.vvsgk.reconciliation_engine.repository.EventRepository;
import com.vvsgk.reconciliation_engine.repository.AuditRecordRepository;
import com.vvsgk.reconciliation_engine.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class ConcurrencyTests {
    @Autowired EventService eventService;
    @Autowired EventRepository eventRepository;
    @Autowired AuditRecordRepository auditRepository;
    @Autowired AccountRepository accountRepository;

    @BeforeEach
    void clearDb() {
        auditRepository.deleteAll();
        eventRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void concurrentDuplicateStorm() throws InterruptedException {
        int threads = 100;
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(ex.submit(() -> {
                latch.await();
                try {
                    eventService.processEvent(new EventRequest("DUP-CONC", Instant.parse("2026-08-15T09:00:00Z"), "ACC-CONC", new BigDecimal("100.00"), "USD", "stress"));
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }));
        }
        latch.countDown();
        ex.shutdown();
        ex.awaitTermination(60, TimeUnit.SECONDS);
        long successes = results.stream().filter(f -> {
            try { return f.get(); } catch (Exception e) { return false; }
        }).count();
        assertEquals(1, eventRepository.count());
        assertEquals(1, auditRepository.count());
        assertEquals(1, accountRepository.count());
        assertEquals(1, successes);
    }

    @Test
    void concurrentSameAccountStorm() throws InterruptedException {
        int threads = 50;
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            results.add(ex.submit(() -> {
                latch.await();
                try {
                    String id = String.format("S%03d", idx);
                    eventService.processEvent(new EventRequest(id, Instant.parse("2026-08-15T09:" + String.format("%02d", idx%60) +":00Z"), "HOT-ACC", new BigDecimal("1.00"), "USD", "stress"));
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }));
        }
        latch.countDown();
        ex.shutdown();
        ex.awaitTermination(60, TimeUnit.SECONDS);
        long successes = results.stream().filter(f -> {
            try { return f.get(); } catch (Exception e) { return false; }
        }).count();
        assertEquals(threads, eventRepository.count());
        assertEquals(threads, auditRepository.count());
        assertEquals(1, accountRepository.count());
        assertEquals(threads, successes);
    }
}
