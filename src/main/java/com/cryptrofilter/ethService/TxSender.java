package com.cryptrofilter.ethService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

public final class TxSender {
    private static final Logger log = LoggerFactory.getLogger(TxSender.class);
    private static final BlockingQueue<Runnable> QUEUE = new LinkedBlockingQueue<>(200);
    private static final ExecutorService ONE = Executors.newSingleThreadExecutor();

    static {
        ONE.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Runnable r = QUEUE.take();
                    try { r.run(); }
                    catch (Throwable t) { log.error("send task failed", t); }
                    Thread.sleep(1000);
                } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        });
    }

    public static void enqueue(Runnable r) {
        if (!QUEUE.offer(r)) log.warn("send queue overflow, drop");
    }
}