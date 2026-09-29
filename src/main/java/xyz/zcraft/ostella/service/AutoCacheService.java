package xyz.zcraft.ostella.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.ostella.data.ScoreType;
import xyz.zcraft.ostella.network.ApiActivity;
import xyz.zcraft.ostella.network.OsuAPI;
import xyz.zcraft.ostella.util.TokenManager;
import xyz.zcraft.osu.model.Score;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Bounded, resumable prefetch: at most one page or file per idle tick. */
public final class AutoCacheService implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(AutoCacheService.class);
    public enum Type {
        BEATMAPSET, BEATMAPSET_JSON, BEATMAP, BEATMAP_JSON;

        public static Type parse(String text) {
            try {
                return valueOf(text.toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Auto cache type must be beatmapset, beatmapset-json, beatmap, or beatmap-json.");
            }
        }
    }

    interface Backend {
        Set<Long> users();
        List<Score> best(long user, int offset);
        void cache(Type type, long id);
    }

    private record Target(Type type, long id) {}
    private final Set<Type> enabled = ConcurrentHashMap.newKeySet();
    private final AtomicLong revision = new AtomicLong();
    private final AsyncService executor;
    private final BooleanSupplier ready;
    private final Backend backend;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("auto-cache").factory());
    private final Deque<Long> users = new ArrayDeque<>();
    private final Deque<Target> targets = new ArrayDeque<>();
    private final Map<Long, Long> refreshed = new HashMap<>();
    private long seenRevision = -1;
    private long nextScan;
    private int offset;
    private volatile boolean closed;

    public AutoCacheService(AsyncService executor, TokenManager tokens) {
        this(executor, tokens::isValid, new Backend() {
            public Set<Long> users() { return CacheService.cachedScoreUsers(); }
            public List<Score> best(long user, int offset) {
                return OsuAPI.getUserScores(tokens.getTokenData(), user, ScoreType.BEST, 100, offset);
            }
            public void cache(Type type, long id) {
                CacheService.prefetch(type, id, tokens.getTokenData());
            }
        });
    }

    AutoCacheService(AsyncService executor, BooleanSupplier ready, Backend backend) {
        this.executor = executor;
        this.ready = ready;
        this.backend = backend;
    }

    public void start() {
        worker.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    public void setEnabled(Type type, boolean value) {
        if (closed) throw new IllegalStateException("Auto cache is stopped");
        boolean changed = value ? enabled.add(type) : enabled.remove(type);
        if (changed) revision.incrementAndGet();
    }

    void tick() {
        long currentRevision = revision.get();
        BooleanSupplier allowed = () -> !closed && !enabled.isEmpty() && ready.getAsBoolean()
                && revision.get() == currentRevision;
        if (!allowed.getAsBoolean()) return;
        try {
            executor.tryBackground(allowed, () -> step(currentRevision));
        } catch (ApiActivity.Yield ignored) {
            // Keep the current page/file queued; foreground work or a toggle interrupted admission.
        } catch (Exception e) {
            LOG.warn("Auto cache step failed; retrying on the next refresh", e);
            if (!targets.isEmpty()) targets.removeFirst();
            else if (!users.isEmpty()) finishUser();
        }
    }

    private void step(long currentRevision) {
        if (seenRevision != currentRevision) {
            users.clear();
            targets.clear();
            refreshed.clear();
            offset = 0;
            nextScan = 0;
            seenRevision = currentRevision;
        }
        if (!targets.isEmpty()) {
            Target target = targets.getFirst();
            ApiActivity.checkBackground();
            if (enabled.contains(target.type())) backend.cache(target.type(), target.id());
            targets.removeFirst();
            return;
        }
        long now = System.currentTimeMillis();
        if (users.isEmpty() && now >= nextScan) {
            nextScan = now + Duration.ofMinutes(5).toMillis();
            Set<Long> currentUsers = backend.users();
            refreshed.keySet().retainAll(currentUsers);
            currentUsers.stream().filter(id -> now >= refreshed.getOrDefault(id, 0L)).forEach(users::addLast);
        }
        if (users.isEmpty()) return;
        ApiActivity.checkBackground();
        List<Score> scores = backend.best(users.getFirst(), offset);
        if (scores == null) throw new IllegalStateException("Missing best-score response");
        Set<Target> unique = new LinkedHashSet<>();
        for (Score score : scores) {
            if (score.getBeatmap() == null) continue;
            Long map = score.getBeatmap().getId();
            Long set = score.getBeatmap().getBeatmapsetId();
            if (score.getBeatmapset() != null) set = score.getBeatmapset().getId();
            for (Type type : enabled) {
                Long id = type == Type.BEATMAP || type == Type.BEATMAP_JSON ? map : set;
                if (id != null && id > 0) unique.add(new Target(type, id));
            }
        }
        targets.addAll(unique);
        if (offset == 100 || scores.size() < 100) finishUser();
        else offset = 100;
    }

    private void finishUser() {
        refreshed.put(users.removeFirst(), System.currentTimeMillis() + Duration.ofHours(1).toMillis());
        offset = 0;
    }

    @Override public void close() {
        closed = true;
        worker.shutdownNow();
    }
}
