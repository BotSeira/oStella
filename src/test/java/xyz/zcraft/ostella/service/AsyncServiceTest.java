package xyz.zcraft.ostella.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncServiceTest {
    private AsyncService service;

    @AfterEach
    void closeService() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    void normalRequestsAreNotBlockedByReplayDownloads() throws Exception {
        service = new AsyncService(100, 1, 1);
        CountDownLatch replayStarted = new CountDownLatch(1);
        CountDownLatch releaseReplay = new CountDownLatch(1);

        CompletableFuture<Void> replay = service.enqueueReplayAsync(() -> {
            replayStarted.countDown();
            await(releaseReplay);
            return null;
        });

        assertTrue(replayStarted.await(1, TimeUnit.SECONDS));
        try {
            assertEquals("normal", service.enqueueAsync(() -> "normal")
                    .get(1, TimeUnit.SECONDS));
        } finally {
            releaseReplay.countDown();
        }
        replay.get(1, TimeUnit.SECONDS);
    }

    @Test
    void replayConcurrencyIsBoundedIndependently() throws Exception {
        service = new AsyncService(100, 1, 1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);

        CompletableFuture<Void> first = service.enqueueReplayAsync(() -> {
            firstStarted.countDown();
            await(releaseFirst);
            return null;
        });
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

        CompletableFuture<Void> second = service.enqueueReplayAsync(() -> {
            secondStarted.countDown();
            return null;
        });

        try {
            assertFalse(secondStarted.await(100, TimeUnit.MILLISECONDS));
        } finally {
            releaseFirst.countDown();
        }

        first.get(1, TimeUnit.SECONDS);
        second.get(1, TimeUnit.SECONDS);
        assertEquals(0, secondStarted.getCount());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        }
    }
}
