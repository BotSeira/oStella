package xyz.zcraft.ostella.network;

import io.javalin.Javalin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import xyz.zcraft.ostella.config.AppConfig;
import xyz.zcraft.ostella.util.TokenManager;

import java.io.Closeable;
import java.io.IOException;

public class WebServer implements Closeable {
    private static final Logger LOG = LogManager.getLogger(WebServer.class);

    private final AppConfig conf;
    private final Javalin app;
    private final Router router;

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

            cfg.routes.before(ctx -> LOG.debug("{} {} {}", ctx.method(), ctx.path(), ctx.queryString()));

            cfg.routes
                    .get("/beatmaps/lookup", router.beatmapController::lookupBeatmap)
                    .get("/beatmaps/{beatmapId}", router.beatmapController::renderBeatmapById)
                    .post("/beatmaps/{beatmapId}/leaderboards", router.leaderboardController::getMapLeaderboard)

                    .get("/beatmapsets/lookup", router.beatmapsetController::lookupBeatmapset)
                    .get("/beatmapsets/search", router::searchBeatmapSet)
                    .get("/beatmapsets/{beatmapsetId}", router.beatmapsetController::renderBeatmapsetById)
                    .get("/beatmapsets/{beatmapsetId}/download", router.beatmapsetController::downloadBeatmapset)

                    .get("/scores/lookup", router.scoreController::lookupScore)
                    .get("/scores/{scoreId}", router.scoreController::renderScoreById)
                    .get("/scores/{scoreId}/analysis", router.analyzeController::renderScoreAnalysisById)
                    .get("/scores/{scoreId}/highlight", router.analyzeController::getScoreHighlight)
                    .get("/scores/{scoreId}/misses", router.analyzeController::getMisses)
                    .get("/scores/{scoreId}/misses/{missIndex}/visualize", router.analyzeController::visualizeMiss)

                    .get("/multiplayer/rooms/current", router::getCurrentRoom)
                    .get("/multiplayer/rooms/current/item", router::getCurrentRoomItem)

                    .post("/users", router::getUsers)
                    .get("/users/me", router::getSelf)
                    .get("/users/me/friends", router::getFriends)
                    .post("/users/leaderboards", router.leaderboardController::getLeaderboard)
                    .get("/users/{userId}/scores/bestof", router::getBestOfN)
                    .get("/users/{userId}/scores/recent", router::getRecentScores)

                    .get("/daily", router::getDaily)
                    .get("/health", router::getServerStatus)
            ;

            if (conf.replayRender().enabled()) {
                cfg.routes
                        .get("/replays/status", router.replayController::getReplayRenderOverview)

                        .post("/replays/renders/score/{scoreId}", router.replayController::queueReplayRenderOfIdAsync)
                        .post("/replays/renders/showcase/scores", router.replayController::renderShowcaseOfIdsAsync)
                        .post("/replays/renders/showcase/{beatmapId}", router.replayController::renderShowcaseOfUsersAsync)

                        .get("/replays/{jobId}/status", router.replayController::getReplayRenderStatus)
                        .get("/replays/{jobId}/video", router.replayController::getReplayRenderResultStream)
                        .get("/replays/{jobId}/video/replay.mp4", router.replayController::getReplayRenderResultFile)
                        .delete("/replays/{jobId}/video", router.replayController::deleteReplayRenderResult);
            } else {
                LOG.info("Replay rendering is disabled.");
            }

            cfg.routes
                    .exception(ApiException.class, (e, ctx) -> {
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
                                 ErrorCode.RENDER_QUEUE_FULL -> ctx.status(500);

                            default -> ctx.status(500);
                        }
                        ctx.result(new Response(false, e.getMessage(), e.getErrorCode().toJson()).toString());
                        if (e.getWrappedException() != null) {
                            LOG.error("API error occurred while processing request: {} - {}", ctx.queryString(), e.getMessage(), e.getWrappedException());
                        } else {
                            LOG.error("API error occurred while processing request: {} - {}", ctx.queryString(), e.getMessage());
                        }
                    })
                    .exception(Exception.class, (e, ctx) -> {
                        ctx.status(500).result(new Response(false, "An error occurred while processing the request!", null).toString());
                        LOG.error("An error occurred while processing request: {}", ctx.queryString(), e);
                    });
        });
    }

    public void start() {
        app.start(conf.webserver().port());
        LOG.info("Started web server on port {}", conf.webserver().port());
    }

    @Override
    public void close() {
        app.stop();
        router.close();
    }
}
