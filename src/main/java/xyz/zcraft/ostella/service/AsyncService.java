package xyz.zcraft.ostella.service;

import java.util.concurrent.*;
import java.util.function.Supplier;

public class AsyncService {
    private final ExecutorService executor;

    private final Object rateLock = new Object();
    private final long requestIntervalNanos;
    private long nextRequestNanos;

    private final Semaphore semaphore = new Semaphore(1, true);

    public AsyncService(int requestPerSecond) {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        requestIntervalNanos = TimeUnit.SECONDS.toNanos(1) / Math.max(1, requestPerSecond);
        nextRequestNanos = System.nanoTime();
    }

    private void acquireStrictRatePermit(Restriction restriction) throws InterruptedException {
        synchronized (rateLock) {
            long waitNanos = nextRequestNanos - System.nanoTime();

            if (waitNanos > 0) {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            }

            nextRequestNanos = System.nanoTime() + requestIntervalNanos * (restriction == Restriction.STRICTER ? 5 : 1);
        }
    }

    public <T> CompletableFuture<T> enqueueAsync(Supplier<T> supplier) {
        return enqueueAsync(supplier, Restriction.NORMAL);
    }

    public <T> CompletableFuture<T> enqueueAsync(Supplier<T> supplier, Restriction restriction) {
        return CompletableFuture.supplyAsync(() -> {
            boolean acquired = false;

            try {
                if (restriction == Restriction.STRICTER) {
                    semaphore.acquire();
                    acquired = true;
                }
                acquireStrictRatePermit(restriction);

                return supplier.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            } finally {
                if (acquired) {
                    semaphore.release();
                }
            }
        }, executor);
    }

    public void close() {
        executor.shutdown();
    }

    public enum Restriction {
        NORMAL, STRICTER
    }
}
