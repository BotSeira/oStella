package xyz.zcraft.ostella.whatif;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.ostella.db.UserStatisticsStore;
import xyz.zcraft.ostella.db.data.UserStatRecord;
import xyz.zcraft.ostella.network.ApiActivity;
import xyz.zcraft.ostella.network.OsuAPI;
import xyz.zcraft.ostella.service.AsyncService;
import xyz.zcraft.ostella.util.TokenManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public final class WhatIfService implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(WhatIfService.class);
    private static final Duration REFRESH_INTERVAL = Duration.ofDays(1);
    private final AtomicBoolean started = new AtomicBoolean();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("whatif-maintenance").factory());
    private final Function<List<Long>, List<UserStatRecord>> fetcher;
    private final Executor executor;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile boolean closed;

    public WhatIfService(Function<List<Long>, List<UserStatRecord>> fetcher, Executor executor) {
        this.fetcher = fetcher;
        this.executor = executor;
    }

    public static WhatIfService create(TokenManager tokens, AsyncService requests) {
        return new WhatIfService(
                ids -> fetchSamples(ids, tokens, requests),
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("whatif-refresh-", 0).factory())
        );
    }

    private static List<UserStatRecord> fetchSamples(List<Long> ids, TokenManager tokens, AsyncService requests) {
        var samples = new ArrayList<UserStatRecord>();
        for (int start = 0; start < ids.size(); start += 50) {
            if (start > 0) {
                try {
                    // noinspection BusyWait
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Whatif refresh interrupted", e);
                }
            }
            List<Long> batch = ids.subList(start, Math.min(start + 50, ids.size()));
            var users = new ArrayList<xyz.zcraft.osu.model.User>();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (true) {
                try {
                    if (requests.tryBackground(() -> tokens.isValid() && !Thread.currentThread().isInterrupted(), () -> {
                        var fetched = OsuAPI.getUsers(tokens.getTokenData(), batch);
                        if (fetched == null) throw new IllegalStateException("Missing whatif users");
                        users.addAll(fetched);
                    })) break;
                } catch (ApiActivity.Yield ignored) { /* Give foreground requests priority. */ }
                if (!tokens.isValid() || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
                    throw new ApiActivity.Yield();
                try {
                    // noinspection BusyWait
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ApiActivity.Yield();
                }
            }
            for (var user : users) {
                var rulesets = user.getStatisticsRulesets();
                var statistics = rulesets == null ? null : rulesets.getOsu();
                if (statistics != null && statistics.getGlobalRank() != null && statistics.getPp() != null) {
                    var sample = UserStatRecord.from(user);
                    if (sample.valid()) samples.add(sample);
                }
            }
        }
        return samples;
    }

    public boolean isRefreshing() {
        return refreshing.get();
    }

    public void start() {
        if (closed || !started.compareAndSet(false, true)) return;
        timer.scheduleWithFixedDelay(() -> {
            try {
                maintain();
            } catch (RuntimeException e) {
                LOG.warn("Whatif maintenance failed", e);
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    @Override
    public void close() {
        closed = true;
        timer.shutdownNow();
        if (executor instanceof ExecutorService worker) worker.shutdownNow();
    }

    public synchronized void observe(UserStatRecord record) {
        if (!closed && record != null && record.valid()) UserStatisticsStore.updateStat(record);
    }

    public synchronized void maintain() {
        if (closed || !refreshing.compareAndSet(false, true)) return;
        try {
            executor.execute(this::refresh);
        } catch (RuntimeException e) {
            refreshing.set(false);
            LOG.warn("Unable to start whatif refresh", e);
        }
    }

    public RankPpModel getModelAround(Double requestedPp, Long requestedRank) {
        if (requestedPp == null && requestedRank == null) return null;

        final Set<UserStatRecord> data;
        if (requestedPp != null) {
            data = UserStatisticsStore.getRecordsAroundPp(requestedPp);
        } else {
            data = UserStatisticsStore.getRecordsAroundRank(requestedRank);
        }

        return RankPpModel.from(data);
    }

    private void refresh() {
        try {
            final List<Long> idsToRefresh = UserStatisticsStore.getSampleUidsByAge(REFRESH_INTERVAL);
            if (idsToRefresh.isEmpty()) return;
            var fetched = fetcher.apply(idsToRefresh).stream()
                    .filter(s -> s != null && s.valid() && idsToRefresh.contains(s.userId()))
                    .toList();
            if (!closed) UserStatisticsStore.updateStat(fetched);
        } catch (Exception e) {
            LOG.warn("Whatif update failed; retaining snapshot and pending samples: {}", e.getMessage());
        } finally {
            refreshing.set(false);
        }
    }
}
