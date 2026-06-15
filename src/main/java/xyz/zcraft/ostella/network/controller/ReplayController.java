package xyz.zcraft.ostella.network.controller;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.javalin.http.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import xyz.zcraft.ostella.config.AppConfig;
import xyz.zcraft.ostella.network.*;
import xyz.zcraft.ostella.service.AsyncService;
import xyz.zcraft.ostella.service.CacheService;
import xyz.zcraft.ostella.service.ReplayService;
import xyz.zcraft.ostella.util.TokenManager;
import xyz.zcraft.osu.model.BeatmapExtended;
import xyz.zcraft.osu.model.Score;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import static xyz.zcraft.ostella.util.RequestUtil.optionalDouble;
import static xyz.zcraft.ostella.util.RequestUtil.requirePathLong;

public class ReplayController {
    private static final Logger LOG = LogManager.getLogger(ReplayController.class);
    private final TokenManager tokenManager;
    private final ReplayService replayService;
    private final AsyncService executor;
    private final AppConfig conf;
    private final Router router;
    private final Gson GSON = new Gson();

    public ReplayController(Router router) {
        this.router = router;
        this.conf = router.conf;
        this.replayService = router.replayService;
        this.tokenManager = router.tokenManager;
        this.executor = router.executor;
    }

    public void getReplayRenderStatus(@NotNull Context context) {
        String jobId = context.pathParam("jobId");
        ReplayService.JobStatus status = replayService.getJobStatus(jobId);

        switch (status) {
            case ReplayService.JobStatus.DONE -> context.status(200).result(
                    new Response(true, "Render complete!",
                            GSON.toJsonTree(Map.of(
                                    "status", "done",
                                    "id", jobId
                            ))).toString());
            case ReplayService.JobStatus.FAILED -> context.status(200).result(
                    new Response(true, "Render failed",
                            GSON.toJsonTree(Map.of(
                                    "status", "failed",
                                    "id", jobId
                            ))).toString());
            case ReplayService.JobStatus.TIMEOUT -> context.status(200).result(
                    new Response(true, "Render timed out",
                            GSON.toJsonTree(Map.of(
                                    "status", "timeout",
                                    "id", jobId
                            ))).toString());
            case ReplayService.JobStatus.QUEUED -> context.status(200).result(
                    new Response(true, "Render is waiting in queue",
                            GSON.toJsonTree(Map.of(
                                    "status", "queued",
                                    "id", jobId
                            ))).toString());
            case ReplayService.JobStatus.RENDERING -> {
                final ReplayService.JobProgress jobProgress = replayService.getJobProgress(jobId);
                JsonObject obj = new JsonObject();
                obj.addProperty("status", "rendering");
                obj.addProperty("id", jobId);
                if (jobProgress != null) {
                    obj.addProperty("progress", jobProgress.progress());
                    obj.addProperty("speed", jobProgress.speed());
                    obj.addProperty("eta", jobProgress.eta());
                }
                context.status(200).result(
                        new Response(true, "Render in progress", obj).toString());
            }
            default -> context.status(404).result(new Response(false, "Job not found", null).toString());
        }
    }

    public void getReplayRenderResultStream(@NotNull Context context) throws IOException {
        String jobId = context.pathParam("jobId");
        Path video = replayService.getJobResult(jobId);

        if (video != null && Files.exists(video)) {
            context.writeSeekableStream(Files.newInputStream(video), "video/mp4");
        } else {
            context.status(404).result("Video expired or not found");
        }
    }

    public void getReplayRenderResultFile(@NotNull Context context) throws IOException {
        String jobId = context.pathParam("jobId");
        Path video = replayService.getJobResult(jobId);

        if (video != null && Files.exists(video)) {
            context.result(Files.newInputStream(video));
        } else {
            context.status(404).result("Video expired or not found");
        }
    }

    public void deleteReplayRenderResult(@NotNull Context context) throws IOException {
        String jobId = context.pathParam("jobId");

        Path video = replayService.getJobResult(jobId);

        replayService.removeJobProgress(jobId);
        replayService.removeJobResult(jobId);

        if (video != null && Files.exists(video)) {
            Files.deleteIfExists(video);
        }

        context.status(200).result("Job cleaned up successfully");
    }

    private CompletionStage<Void> finalizeReplay(@NotNull Context context, Score score) {
        final double start = optionalDouble(context, "start");
        final double end = optionalDouble(context, "end");

        router.ensurePp(score);

        return renderScoreForAsync(context, score, start, end);
    }

    public void queueReplayRenderOfIdAsync(@NotNull Context context) {
        long scoreId = requirePathLong(context, "scoreId");
        context.future(() ->
                router.getScore(scoreId).thenCompose(score -> {
                    if (score == null) {
                        throw new ApiException(ErrorCode.NO_SCORE_FOUND, "No score found for this ID!");
                    }

                    return finalizeReplay(context, score);
                })
        );
    }

    public void getReplayRenderOverview(@NotNull Context context) {
        context.status(200).result(String.valueOf(new Response(true, "", GSON.toJsonTree(Map.of(
                "enabled", replayService != null,
                "queue", replayService != null ? replayService.getQueueSize() : 0
        )))));
    }

    public void renderShowcaseOfIdsAsync(@NotNull Context context) {
        if (replayService == null) return;

        final ShowcaseRequest showcaseRequest = GSON.fromJson(context.body(), ShowcaseRequest.class);
        final List<Long> scoreIds = showcaseRequest.ids();

        context.future(() -> {
            List<CompletableFuture<Score>> scoreFutures = scoreIds.stream()
                    .map(router::getScore).toList();

            return finalizeShowcase(context, scoreFutures);
        });
    }

    public void renderShowcaseOfUsersAsync(@NotNull Context context) {
        if (replayService == null) return;

        final ShowcaseRequest showcaseRequest = GSON.fromJson(context.body(), ShowcaseRequest.class);

        final long m = requirePathLong(context, "beatmapId");
        final List<Long> userIds = showcaseRequest.ids();

        context.future(() -> {
            LOG.info("Getting {} scores for showcase on map {}", userIds.size(), m);

            List<CompletableFuture<Score>> scoreFutures = userIds.stream()
                    .map(userId -> executor.enqueueAsync(() ->
                            OsuAPI.getUserScore(tokenManager.getTokenData(), userId, m))).toList();

            return finalizeShowcase(context, scoreFutures);
        });
    }

    @NonNull
    private CompletableFuture<?> finalizeShowcase(@NotNull Context context, List<CompletableFuture<Score>> scoreFutures) {
        return CompletableFuture.allOf(scoreFutures.toArray(new CompletableFuture[0]))
                .thenApply(_ -> scoreFutures.stream()
                        .map(CompletableFuture::join)
                        .filter(s -> s != null && s.getHasReplay())
                        .peek(router::ensurePp)
                        .collect(Collectors.toCollection(LinkedList::new))
                )
                .thenCompose(validScores -> {
                    final double start = optionalDouble(context, "start");
                    final double end = optionalDouble(context, "end");

                    return renderShowcaseForAsync(context, validScores, start, end);
                });
    }

    private CompletableFuture<Void> renderScoreForAsync(@NotNull Context context, Score score, Double start, Double end) {
        if (replayService == null) return CompletableFuture.completedFuture(null);

        if (!score.getHasReplay()) {
            throw new ApiException(ErrorCode.REPLAY_UNAVAILABLE, "Replay unavailable!");
        }

        return executor.enqueueAsync(() -> {
            if (!CacheService.cacheBeatmapsetFile(score.getBeatmapset().getId())) {
                throw new ApiException(ErrorCode.BEATMAPSET_FETCH_FAILED, "Failed to cache beatmapset!");
            }
            return null;
        }).thenCompose(_ ->
                executor.enqueueAsync(() -> {
                    try {
                        return CacheService.getReplay(tokenManager.getTokenData(), score.getId());
                    } catch (IOException e) {
                        throw new ApiException(ErrorCode.REPLAY_FETCH_FAILED, "Failed to cache replay for score id: " + score.getId(), e);
                    }
                })
        ).thenAccept(replayPath -> {
            final int queueSize = replayService.getQueueSize() + 1;

            if (queueSize > conf.replayRender().renderQueueSize()) {
                throw new ApiException(ErrorCode.RENDER_QUEUE_FULL, "Replay rendering queue is full!");
            }

            final String jobId = replayService.queueRender(replayPath, start, end);

            score.getBeatmap().setBeatmapset(score.getBeatmapset());

            JsonObject obj = new JsonObject();
            obj.addProperty("status", "queued");
            obj.addProperty("position", queueSize);
            obj.addProperty("id", jobId);
            obj.add("beatmap", GSON.toJsonTree(score.getBeatmap()));
            obj.add("scores", router.getScoresArr(List.of(score)));

            if (!Double.isNaN(start)) obj.addProperty("start", start);
            if (!Double.isNaN(end)) obj.addProperty("end", end);

            context.status(202).result(new Response(true, "Replay render queued!", obj).toString());
        });
    }

    private CompletableFuture<Void> renderShowcaseForAsync(@NotNull Context context, LinkedList<Score> scores, Double start, Double end) {
        if (replayService == null) return CompletableFuture.completedFuture(null);

        if (scores.isEmpty()) {
            throw new ApiException(ErrorCode.NO_SCORE_FOUND, "No valid scores found!");
        }

        final long beatmapId = scores.getFirst().getBeatmap().getId();
        final long beatmapsetId = scores.getFirst().getBeatmap().getBeatmapsetId();

        CompletableFuture<Boolean> cacheFuture = executor.enqueueAsync(() ->
                CacheService.cacheBeatmapsetFile(beatmapsetId));

        CompletableFuture<BeatmapExtended> beatmapFuture = executor.enqueueAsync(() ->
                OsuAPI.getBeatmap(tokenManager.getTokenData(), beatmapId));

        return CompletableFuture.allOf(cacheFuture, beatmapFuture).thenCompose(_ -> {
            if (!cacheFuture.join())
                throw new ApiException(ErrorCode.BEATMAPSET_FETCH_FAILED, "Failed to cache beatmapset!");
            BeatmapExtended beatmap = beatmapFuture.join();
            if (beatmap == null) throw new ApiException(ErrorCode.BEATMAP_FETCH_FAILED, "Failed to get beatmap!");

            List<CompletableFuture<Path>> replayFutures = scores.stream()
                    .map(score -> executor.enqueueAsync(() -> {
                        try {
                            return CacheService.getReplay(tokenManager.getTokenData(), score.getId());
                        } catch (IOException e) {
                            LOG.error("Failed to cache replay for score id: {}", score.getId(), e);
                            return null;
                        }
                    }))
                    .toList();

            return CompletableFuture.allOf(replayFutures.toArray(new CompletableFuture[0]))
                    .thenAccept(_ -> {
                        LinkedList<Path> replays = replayFutures.stream()
                                .map(CompletableFuture::join)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toCollection(LinkedList::new));

                        final int queueSize = replayService.getQueueSize() + 1;
                        if (queueSize > conf.replayRender().renderQueueSize()) {
                            throw new ApiException(ErrorCode.RENDER_QUEUE_FULL, "Render queue full!");
                        }

                        final String jobId = replayService.queueRenderShowcase(String.valueOf(beatmapId), replays, start, end);

                        JsonObject obj = new JsonObject();
                        obj.addProperty("status", "queued");
                        obj.addProperty("position", queueSize);
                        obj.addProperty("id", jobId);
                        obj.add("beatmap", GSON.toJsonTree(beatmap));
                        obj.add("scores", router.getScoresArr(scores));

                        context.status(202).result(new Response(true, "Replay render queued!", obj).toString());
                    });
        });
    }

    public record ShowcaseRequest(List<Long> ids) {
    }
}
