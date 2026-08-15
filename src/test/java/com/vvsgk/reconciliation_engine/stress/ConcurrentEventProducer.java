package com.vvsgk.reconciliation_engine.stress;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ConcurrentEventProducer {
    public void produce(Runnable task, int threads) throws InterruptedException {
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) ex.submit(task);
        ex.shutdown();
        while (!ex.isTerminated()) Thread.sleep(50);
    }
}
