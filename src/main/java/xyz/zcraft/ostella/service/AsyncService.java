package xyz.zcraft.ostella.service;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class AsyncService {
    private final ExecutorService requestExecutor;
    private final ExecutorService replayExecutor;
    private final StrictRateGate requestRateGate;
    private final StrictRateGate replayRateGate;
    private final Semaphore replaySemaphore;
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicInteger activeRequests = new AtomicInteger();
    private final AtomicInteger activeReplays = new AtomicInteger();

    public AsyncService(int requestPerSecond, int replayRequestIntervalMillis, int replayMaxConcurrent) {
        requestExecutor = Executors.newVirtualThreadPerTaskExecutor();
        replayExecutor = Executors.newVirtualThreadPerTaskExecutor();
        requestRateGate = new StrictRateGate(
                TimeUnit.SECONDS.toNanos(1) / Math.max(1, requestPerSecond));
        replayRateGate = new StrictRateGate(
                TimeUnit.MILLISECONDS.toNanos(Math.max(1, replayRequestIntervalMillis)));
        replaySemaphore = new Semaphore(Math.max(1, replayMaxConcurrent), true);
    }

    public <T> CompletableFuture<T> enqueueAsync(Supplier<T> supplier) {
        return enqueueAsync(supplier, requestExecutor, requestRateGate, null, activeRequests);
    }

    public <T> CompletableFuture<T> enqueueReplayAsync(Supplier<T> supplier) {
        return enqueueAsync(supplier, replayExecutor, replayRateGate, replaySemaphore, activeReplays);
    }

    private <T> CompletableFuture<T> enqueueAsync(
            Supplier<T> supplier,
            ExecutorService targetExecutor,
            StrictRateGate rateGate,
            Semaphore concurrencyLimit,
            AtomicInteger active
    ) {
        submitted.incrementAndGet();
        return CompletableFuture.supplyAsync(() -> {
            boolean acquired = false;

            try {
                if (concurrencyLimit != null) {
                    concurrencyLimit.acquire();
                    acquired = true;
                }
                rateGate.acquire();
                active.incrementAndGet();
                try {
                    T result = supplier.get();
                    completed.incrementAndGet();
                    return result;
                } catch (RuntimeException e) {
                    failed.incrementAndGet();
                    throw e;
                } finally {
                    active.decrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            } finally {
                if (acquired) {
                    concurrencyLimit.release();
                }
            }
        }, targetExecutor);
    }

    public Status status() {
        return new Status(submitted.get(), completed.get(), failed.get(), activeRequests.get(), activeReplays.get());
    }

    public void close() {
        requestExecutor.shutdown();
        replayExecutor.shutdown();
    }

    private static final class StrictRateGate {
        private final long intervalNanos;
        private long nextRequestNanos = System.nanoTime();

        private StrictRateGate(long intervalNanos) {
            this.intervalNanos = intervalNanos;
        }

        private synchronized void acquire() throws InterruptedException {
            long waitNanos;
            while ((waitNanos = nextRequestNanos - System.nanoTime()) > 0) {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            }
            nextRequestNanos = System.nanoTime() + intervalNanos;
        }
    }

    public record Status(long submitted, long completed, long failed, int activeRequests, int activeReplays) {
    }
}
