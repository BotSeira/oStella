package xyz.zcraft.ostella.service;

import com.google.common.util.concurrent.RateLimiter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

public class AsyncService {
    private final ExecutorService executor;

    @SuppressWarnings("UnstableApiUsage")
    private final RateLimiter rateLimiter;

    private final Semaphore semaphore = new Semaphore(4);

    public AsyncService(int requestPerSecond) {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        //noinspection UnstableApiUsage
        this.rateLimiter = RateLimiter.create(Math.max(1, requestPerSecond));
    }

    public <T> CompletableFuture<T> enqueueAsync(Supplier<T> supplier) {
        return enqueueAsync(supplier, false);
    }

    public <T> CompletableFuture<T> enqueueAsync(Supplier<T> supplier, boolean limitConcurrent) {
        return CompletableFuture.supplyAsync(() -> {
            if (limitConcurrent) {
                try {
                    semaphore.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
            //noinspection UnstableApiUsage
            rateLimiter.acquire();
            return supplier.get();
        }, executor);
    }

    public void close() {
        executor.shutdown();
    }
}
