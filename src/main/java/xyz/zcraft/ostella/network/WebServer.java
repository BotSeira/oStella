package xyz.zcraft.ostella.network;

import io.javalin.Javalin;
import io.javalin.http.UnauthorizedResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import xyz.zcraft.ostella.config.AppConfig;
import xyz.zcraft.ostella.exception.ApiException;
import xyz.zcraft.ostella.service.CacheService;
import xyz.zcraft.ostella.service.ReplayService;
import xyz.zcraft.ostella.util.TokenManager;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class WebServer implements Closeable {
    private static final Logger LOG = LogManager.getLogger(WebServer.class);

    private final AppConfig conf;
    private final Javalin app;
    private final Router router;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public WebServer(AppConfig conf, TokenManager tokenManager) throws IOException {
        this.conf = conf;
        this.router = new Router(conf, tokenManager);
        app = Javalin.create(cfg -> {
            final QueuedThreadPool threadPool = new QueuedThreadPool(
                    Math.max(8, conf.webserver().maxThreads() + 3),
                    Math.max(2, conf.webserver().minThreads()),
                    Math.max(1000, conf.webserver().idleTimeout())
            );
            threadPool.setName("ServPool");
            cfg.jetty.threadPool = threadPool;

            cfg.routes.beforeMatched(ctx -> {
                final String token = conf.ostella().token();
                if (token != null && !token.isBlank()) {
                    final String header = ctx.header(Headers.SERVICE_AUTHORIZATION);
                    if (header == null || !header.equals("Bearer " + token)) {
                        ctx.status(401).result("Unauthorized");
                        throw new UnauthorizedResponse();
                    }
                }
            });

            cfg.routes.before(ctx -> {
                requests.incrementAndGet();
                LOG.debug("{} {} {}", ctx.method(), ctx.path(), ctx.queryString());
            });

            cfg.routes
                    .get("/beatmaps/lookup", router.beatmapController::lookupBeatmap)
                    .get("/beatmaps/{beatmapId}/analysis", router.beatmapController::getBeatmapAnalysisById)
                    .get("/beatmaps/{beatmapId}", router.beatmapController::getBeatmapById)
                    .post("/beatmaps/{beatmapId}/leaderboards", router.leaderboardController::getMapLeaderboard)
                    .get("/beatmaps/{beatmapId}/background", router.beatmapController::getBackground)

                    .get("/beatmapsets/lookup", router.beatmapsetController::lookupBeatmapset)
                    .get("/beatmapsets/search", router.beatmapsetController::searchBeatmapset)
                    .get("/beatmapsets/{beatmapsetId}", router.beatmapsetController::getBeatmapsetById)
                    .get("/beatmapsets/{beatmapsetId}/background", router.beatmapsetController::getBeatmapsetBg)
                    .get("/beatmapsets/{beatmapsetId}/download", router.beatmapsetController::downloadBeatmapset)

                    .get("/scores/lookup", router.scoreController::lookupScore)
                    .get("/scores/random", router.scoreController::randomScore)
                    .post("/scores/random/users", router.scoreController::randomScoreFromUsers)
                    .get("/scores/random/users/{userId}/weights", router.scoreController::randomScoreFromUsersWeights)
                    .post("/scores/random/users/{userId}/weights", router.scoreController::randomScoreFromUsersWeights)
                    .get("/scores/{scoreId}", router.scoreController::getScoreById)
                    .get("/scores/{scoreId}/analysis", router.analyzeController::getScoreAnalysisById)
                    .get("/scores/{scoreId}/highlight", router.analyzeController::getScoreHighlight)
                    .get("/scores/{scoreId}/misses", router.analyzeController::getMisses)
                    .get("/scores/{scoreId}/misses/{missIndex}/visualize", router.analyzeController::visualizeMiss)

                    .get("/multiplayer/rooms/current", router.multiplayerController::getCurrentRoom)
                    .get("/multiplayer/rooms/current/item", router.multiplayerController::getCurrentRoomItem)
                    .get("/multiplayer/rooms/{roomId}/watch", router.multiplayerController::getRoomWatchState)
                    .get("/multiplayer/rooms/{roomId}/playlist/{playlistItemId}/result",
                            router.multiplayerController::getRoomResult)

                    .post("/users", router.userController::getUsers)
                    .post("/users/lookup", router.userController::lookupUser)
                    .get("/users/me", router.userController::getSelf)
                    .get("/users/me/friends", router.userController::getFriends)
                    .post("/users/leaderboards", router.leaderboardController::getLeaderboard)
                    .post("/users/scores/recent/batch", router.userController::getRecentScoresBatch)
                    .get("/users/{userId}", router.userController::getUserInfo)
                    .get("/users/{userId}/rank", router.userController::getUserRank)
                    .get("/users/{userId}/scores/bestof", router.userController::getBestOfN)
                    .get("/users/{userId}/scores/recent", router.userController::getRecentScores)
                    .get("/users/{userId}/scores/today-best", router.userController::getTodayBestScores)

                    .get("/daily", router::getDaily)
                    .get("/health", router::getServerStatus)
                    .post("/cache/control", router::controlCache)

                    .post("/templates/{templateName}/render", router::renderCustomTemplate)

                    .get("/replays/status", router.replayController::getReplayRenderOverview)
                    .post("/replays/upload", router.replayController::uploadReplay)
            ;

            if (conf.replayRender().enabled()) {
                cfg.routes
                        .post("/replays/renders/score/{scoreId}", router.replayController::queueReplayRenderOfId)
                        .post("/replays/renders/preview/{beatmapId}", router.replayController::renderBeatmapPreview)
                        .post("/replays/renders/showcase/scores", router.replayController::renderShowcaseOfIds)
                        .post("/replays/renders/showcase/{beatmapId}", router.replayController::renderShowcaseOfUsers)

                        .get("/replays/{jobId}/status", router.replayController::getReplayRenderStatus)
                        .post("/replays/{jobId}/cancel", router.replayController::cancelReplayRender)
                        .get("/replays/{jobId}/video", router.replayController::getReplayRenderResultStream)
                        .get("/replays/{jobId}/video/replay.mp4", router.replayController::getReplayRenderResultFile)
                        .delete("/replays/{jobId}/video", router.replayController::deleteReplayRenderResult);
            } else {
                LOG.info("Replay rendering is disabled.");
            }

            cfg.routes
                    .exception(ApiException.class, (e, ctx) -> {
                        failures.incrementAndGet();
                        switch (e.getErrorCode()) {
                            case ErrorCode.NO_BEATMAP_FOUND,
                                 ErrorCode.NO_BEATMAPSET_FOUND,
                                 ErrorCode.NO_SCORE_FOUND,
                                 ErrorCode.NO_ROOM_FOUND,
                                 ErrorCode.NO_USER_FOUND -> ctx.status(404);

                            case ErrorCode.UNAUTHORIZED -> ctx.status(401);

                            case ErrorCode.ILLEGAL_ARGUMENT,
                                 ErrorCode.REPLAY_UNAVAILABLE -> ctx.status(400);

                            case ErrorCode.BEATMAP_FETCH_FAILED,
                                 ErrorCode.BEATMAPSET_FETCH_FAILED,
                                 ErrorCode.SCORE_FETCH_FAILED,
                                 ErrorCode.USER_FETCH_FAILED,
                                 ErrorCode.RENDER_QUEUE_FULL -> ctx.status(429);

                            case ErrorCode.RENDERER_UNAVAILABLE,
                                 ErrorCode.PERFORMANCE_PLUS_UNAVAILABLE -> ctx.status(502);

                            default -> ctx.status(500);
                        }
                        ctx.contentType("application/json").result(new Response(false, e.getMessage(), e.getErrorCode().toJson()).toString());
                        if (e.getWrappedException() != null) {
                            LOG.error("API error occurred while processing request: {} - {}", ctx.queryString(), e.getMessage(), e.getWrappedException());
                        } else {
                            LOG.error("API error occurred while processing request: {} - {}", ctx.queryString(), e.getMessage());
                        }
                    })
                    .exception(Exception.class, (e, ctx) -> {
                        failures.incrementAndGet();
                        ctx.status(500).contentType("application/json").result(new Response(false, "An error occurred while processing the request!", null).toString());
                        LOG.error("An error occurred while processing request: {}", ctx.queryString(), e);
                    });
        });
    }

    public void start() {
        app.start(conf.webserver().port());
        running.set(true);
        router.autoCache.start();
        LOG.info("Started web server on port {}", conf.webserver().port());
    }

    public ServerStatus status() {
        return new ServerStatus(
                running.get() && !closed.get(),
                requests.get(),
                failures.get(),
                router.tokenManager.isValid(),
                router.executor.status(),
                router.renderer.status(),
                conf.replayRender().enabled(),
                router.replayService.workerCount(),
                router.replayService.assignedJobCount(),
                CacheService.summary()
        );
    }

    public boolean requestTokenRenewal() {
        return router.tokenManager.requestRenewal();
    }

    public int replayQueueSize() {
        return router.replayService.getQueueSize();
    }

    public ReplayService.JobProgress replayJob(String jobId) {
        return router.replayService.getJobProgress(jobId);
    }

    public void deleteReplayJob(String jobId) {
        router.replayService.deleteJob(jobId);
    }

    public void setAutoCache(xyz.zcraft.ostella.service.AutoCacheService.Type type, boolean enabled) {
        router.autoCache.setEnabled(type, enabled);
    }

    public int clearCache(CacheService.CacheArea area) {
        return CacheService.clear(area);
    }

    public xyz.zcraft.ostella.cache.CacheControlResult controlCache(
            xyz.zcraft.ostella.cache.CacheControlRequest request) {
        return router.controlCache(request);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            running.set(false);
            app.stop();
            router.close();
        }
    }

    public record ServerStatus(
            boolean running,
            long requests,
            long failures,
            boolean tokenValid,
            xyz.zcraft.ostella.service.AsyncService.Status async,
            xyz.zcraft.ostella.service.RenderService.Status renderer,
            boolean replayEnabled,
            int replayWorkers,
            int assignedReplayJobs,
            xyz.zcraft.ostella.service.CacheService.CacheSummary cache
    ) {
    }
}
