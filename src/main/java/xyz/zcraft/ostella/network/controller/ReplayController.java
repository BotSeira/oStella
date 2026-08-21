package xyz.zcraft.ostella.network.controller;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.javalin.http.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import xyz.zcraft.ostella.config.AppConfig;
import xyz.zcraft.ostella.data.ScoreId;
import xyz.zcraft.ostella.exception.ApiException;
import xyz.zcraft.ostella.network.ErrorCode;
import xyz.zcraft.ostella.network.OsuAPI;
import xyz.zcraft.ostella.network.Response;
import xyz.zcraft.ostella.network.Router;
import xyz.zcraft.ostella.service.AsyncService;
import xyz.zcraft.ostella.service.BeatmapPreviewService;
import xyz.zcraft.ostella.service.CacheService;
import xyz.zcraft.ostella.service.LocalScoreService;
import xyz.zcraft.ostella.service.ReplayService;
import xyz.zcraft.ostella.util.TokenManager;
import xyz.zcraft.osu.model.BeatmapExtended;
import xyz.zcraft.osu.model.Score;
import xyz.zcraft.osu.parser.BeatmapParser;
import xyz.zcraft.osu.parser.ReplayParser;
import xyz.zcraft.osu.parser.data.beatmap.OsuBeatmap;
import xyz.zcraft.osu.parser.data.replay.OsuReplay;
import xyz.zcraft.osu.parser.exception.AnalyzeException;
import xyz.zcraft.osu.parser.exception.ParseException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import static xyz.zcraft.ostella.util.RequestUtil.*;

public class ReplayController {
    private static final Logger LOG = LogManager.getLogger(ReplayController.class);
    private final TokenManager tokenManager;
    private final ReplayService replayService;
    private final AsyncService executor;
    private final AppConfig conf;
    private final Router router;
    private final LocalScoreService localScoreService;
    private final Gson GSON = new Gson();
    private final ConcurrentMap<Long, CompletableFuture<Path>> replayFetches = new ConcurrentHashMap<>();

    public ReplayController(Router router) {
        this.router = router;
        this.conf = router.conf;
        this.replayService = router.replayService;
        this.tokenManager = router.tokenManager;
        this.executor = router.executor;
        this.localScoreService = new LocalScoreService(tokenManager);
    }

    public void getReplayRenderStatus(@NotNull Context context) {
        String jobId = context.pathParam("jobId");
        ReplayService.JobProgress jobProgress = replayService.getJobProgress(jobId);
        ReplayService.JobStatus status = jobProgress.status();

        switch (status) {
            case ReplayService.JobStatus.DONE -> {
                JsonObject obj = new JsonObject();
                obj.addProperty("status", "done");
                obj.addProperty("id", jobId);
                if (jobProgress.qqFile() != null) {
                    obj.add("qqFile", jobProgress.qqFile());
                }
                if (jobProgress.error() != null) {
                    obj.addProperty("qqUploadError", jobProgress.error());
                }
                context.status(200).result(new Response(true, "Render complete!", obj).toString());
            }
            case ReplayService.JobStatus.FAILED -> respondTerminalStatus(
                    context, jobId, "failed", "Render failed", jobProgress.error());
            case ReplayService.JobStatus.TIMEOUT -> respondTerminalStatus(
                    context, jobId, "timeout", "Render timed out", jobProgress.error());
            case ReplayService.JobStatus.QUEUED -> context.status(200).result(
                    new Response(true, "Render is waiting in queue",
                            GSON.toJsonTree(Map.of(
                                    "status", "queued",
                                    "id", jobId
                            ))).toString());
            case ReplayService.JobStatus.RENDERING -> {
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
            case ReplayService.JobStatus.UPLOADING -> {
                JsonObject obj = new JsonObject();
                obj.addProperty("status", "uploading");
                obj.addProperty("id", jobId);
                context.status(200).result(
                        new Response(true, "Uploading render to QQ", obj).toString());
            }
            default -> context.status(404).result(new Response(false, "Job not found", null).toString());
        }
    }

    public void getReplayRenderResultStream(@NotNull Context context) throws IOException {
        String jobId = context.pathParam("jobId");
        var video = replayService.openJobResult(jobId);

        if (video != null) {
            context.contentType("video/mp4").result(video);
        } else {
            context.status(404).result("Video expired or not found");
        }
    }

    public void getReplayRenderResultFile(@NotNull Context context) throws IOException {
        String jobId = context.pathParam("jobId");
        var video = replayService.openJobResult(jobId);

        if (video != null) {
            context.contentType("video/mp4").result(video);
        } else {
            context.status(404).result("Video expired or not found");
        }
    }

    public void deleteReplayRenderResult(@NotNull Context context) throws IOException {
        String jobId = context.pathParam("jobId");
        replayService.deleteJob(jobId);
        context.status(200).result("Job cleaned up successfully");
    }

    private CompletionStage<Void> finalizeReplay(@NotNull Context context, Score score,
                                                  ReplayService.QqUploadRequest qqUpload) {
        final double start = optionalDouble(context, "start");
        final double end = optionalDouble(context, "end");
        final boolean obscured = optionalBoolean(context, "obscured", false);

        router.ensurePp(score);

        return renderScoreForAsync(context, score, start, end, obscured, qqUpload);
    }

    public void queueReplayRenderOfId(@NotNull Context context) {
        long scoreId = requirePathScoreId(context, "scoreId");
        ReplayService.QqUploadRequest qqUpload = parseQqUpload(context);
        context.future(() ->
                router.getScore(scoreId).thenCompose(score -> {
                    if (score == null) {
                        throw new ApiException(ErrorCode.NO_SCORE_FOUND, "No score found for this ID!");
                    }

                    return finalizeReplay(context, score, qqUpload);
                })
        );
    }

    public void getReplayRenderOverview(@NotNull Context context) {
        int queueSize = conf.replayRender().enabled() ? replayService.getQueueSize() : 0;
        context.status(200).result(String.valueOf(new Response(true, "", GSON.toJsonTree(Map.of(
                "enabled", conf.replayRender().enabled(),
                "queue", queueSize
        )))));
    }

    public void renderShowcaseOfIds(@NotNull Context context) {
        if (replayService == null) return;

        final ShowcaseRequest showcaseRequest = GSON.fromJson(context.body(), ShowcaseRequest.class);
        final List<String> scoreIds = showcaseRequest.ids();

        context.future(() -> {
            List<CompletableFuture<Score>> scoreFutures = scoreIds.stream()
                    .map(ScoreId::parse)
                    .map(router::getScore)
                    .toList();

            return finalizeShowcase(context, scoreFutures, showcaseRequest.qqUpload());
        });
    }

    public void renderShowcaseOfUsers(@NotNull Context context) {
        if (replayService == null) return;

        final ShowcaseRequest showcaseRequest = GSON.fromJson(context.body(), ShowcaseRequest.class);

        final long m = requirePathLong(context, "beatmapId");
        final List<String> ids = showcaseRequest.ids();

        final Set<Long> scoreIds = new HashSet<>();

        ids.stream().filter(id -> id.startsWith("s")).map(id -> id.substring(1)).map(ScoreId::parse).forEach(scoreIds::add);

        context.future(() -> {
            LOG.info("Getting {} scores for showcase on map {}", ids.size(), m);

            List<CompletableFuture<Score>> scoreFutures = new ArrayList<>(ids.stream()
                    .filter(id -> !id.startsWith("s"))
                    .map(id -> {
                        if (id.startsWith("u")) return id.substring(1);
                        return id;
                    })
                    .map(Long::parseLong)
                    .map(userId -> executor.enqueueAsync(() -> OsuAPI.getUserScore(tokenManager.getTokenData(), userId, m)))
                    .toList());

            scoreFutures.addAll(scoreIds.stream().map(router::getScore).toList());

            return finalizeShowcase(context, scoreFutures, showcaseRequest.qqUpload());
        });
    }

    private void respondTerminalStatus(Context context, String jobId, String status,
                                       String message, String error) {
        JsonObject obj = terminalStatusData(jobId, status, error);
        context.status(200).result(new Response(true, message, obj).toString());
    }

    static JsonObject terminalStatusData(String jobId, String status, String error) {
        JsonObject obj = new JsonObject();
        obj.addProperty("status", status);
        obj.addProperty("id", jobId);
        if (error != null && !error.isBlank()) {
            obj.addProperty("error", error);
        }
        return obj;
    }

    public void renderBeatmapPreview(@NotNull Context context) {
        if (replayService == null) return;

        final long beatmapId = requirePathLong(context, "beatmapId");
        final PreviewRequest request;
        final String mods;
        try {
            request = context.body() == null || context.body().isBlank()
                    ? new PreviewRequest(null, null)
                    : GSON.fromJson(context.body(), PreviewRequest.class);
            mods = BeatmapPreviewService.normalizeMods(request == null ? null : request.mods());
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, e.getMessage(), e);
        }

        context.future(() -> executor.enqueueAsync(() -> OsuAPI.getBeatmap(tokenManager.getTokenData(), beatmapId))
                .thenCompose(beatmap -> executor.enqueueAsync(() -> {
                    if (beatmap == null) {
                        throw new ApiException(ErrorCode.NO_BEATMAP_FOUND, "No beatmap found");
                    }
                    if (beatmap.getModeInt() != null && beatmap.getModeInt() != 0) {
                        throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT,
                                "Only osu!standard beatmaps can be previewed");
                    }

                    long beatmapsetId = beatmap.getBeatmapsetId();
                    if (!CacheService.cacheBeatmapsetFile(beatmapsetId)) {
                        throw new ApiException(ErrorCode.BEATMAPSET_FETCH_FAILED,
                                "Failed to cache beatmapset");
                    }

                    try {
                        OsuBeatmap parsed = BeatmapParser.parseBeatmap(CacheService.getBeatmapPath(beatmapId));
                        BeatmapPreviewService.PreviewSegment segment =
                                BeatmapPreviewService.selectSegment(parsed, mods);
                        Path beatmapset = CacheService.getBeatmapsetArchivePath(beatmapsetId);
                        ReplayService.QueuedJob queued = replayService.queueRenderPreview(
                                beatmapId,
                                beatmapsetId,
                                beatmapset,
                                segment.start(),
                                segment.end(),
                                mods,
                                request == null ? null : request.qqUpload()
                        );

                        JsonObject obj = new JsonObject();
                        obj.addProperty("status", "queued");
                        obj.addProperty("position", queued.position());
                        obj.addProperty("id", queued.id());
                        obj.addProperty("start", segment.start());
                        obj.addProperty("end", segment.end());
                        obj.addProperty("selection", segment.reason());
                        obj.addProperty("mods", mods.isEmpty() ? "NM" : mods);
                        obj.add("beatmap", GSON.toJsonTree(beatmap));

                        context.status(202).result(
                                new Response(true, "Beatmap preview render queued!", obj).toString());
                        return null;
                    } catch (ParseException e) {
                        throw new ApiException(ErrorCode.BEATMAP_PARSE_FAILED, e);
                    } catch (AnalyzeException e) {
                        throw new ApiException(ErrorCode.BEATMAP_PARSE_FAILED,
                                "Failed to select a beatmap preview segment", e);
                    } catch (IOException e) {
                        throw new ApiException(ErrorCode.BEATMAPSET_FETCH_FAILED,
                                "Failed to prepare beatmapset for osuRenderer", e);
                    } catch (IllegalArgumentException e) {
                        throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, e.getMessage(), e);
                    }
                })));
    }

    @NonNull
    private CompletableFuture<?> finalizeShowcase(@NotNull Context context,
                                                  List<CompletableFuture<Score>> scoreFutures,
                                                  ReplayService.QqUploadRequest qqUpload) {
        return CompletableFuture.allOf(
                        scoreFutures.toArray(new CompletableFuture<?>[0])
                )
                .thenApply(ignored -> {
                    Set<Long> seenIds = new HashSet<>();

                    return scoreFutures.stream()
                            .map(CompletableFuture::join)
                            .filter(Objects::nonNull)
                            .filter(score -> CacheService.hasReplayCache(score.getId()) || score.getHasReplay())
                            .filter(score -> seenIds.add(score.getId()))
                            .peek(router::ensurePp)
                            .collect(Collectors.toCollection(LinkedList::new));
                })
                .thenCompose(validScores ->
                        renderShowcaseForAsync(context, validScores, qqUpload)
                );
    }

    private CompletableFuture<Void> renderScoreForAsync(@NotNull Context context, Score score, Double start,
                                                         Double end, boolean obscured,
                                                         ReplayService.QqUploadRequest qqUpload) {
        if (replayService == null) return CompletableFuture.completedFuture(null);

        if (!CacheService.hasReplayCache(score.getId()) && !score.getHasReplay()) {
            throw new ApiException(ErrorCode.REPLAY_UNAVAILABLE, "Replay unavailable!");
        }

        return executor.enqueueAsync(() -> {
                    if (!CacheService.cacheBeatmapsetFile(score.getBeatmapset().getId())) {
                        throw new ApiException(ErrorCode.BEATMAPSET_FETCH_FAILED, "Failed to cache beatmapset!");
                    }
                    return null;
                })
                .thenCompose(_ -> router.replayController.getReplayFuture(score.getId()))
                .thenAccept(replayPath -> {
                    final Path beatmapset;
                    try {
                        beatmapset = CacheService.getBeatmapsetArchivePath(score.getBeatmapset().getId());
                    } catch (IOException e) {
                        throw new ApiException(ErrorCode.BEATMAPSET_FETCH_FAILED,
                                "Failed to prepare beatmapset for osuRenderer", e);
                    }
                    final ReplayService.QueuedJob queued =
                            replayService.queueRender(ScoreId.renderAssetId(score.getId()), replayPath,
                                    score.getBeatmapset().getId(), beatmapset, start, end, obscured, qqUpload);

                    score.getBeatmap().setBeatmapset(score.getBeatmapset());

                    JsonObject obj = new JsonObject();
                    obj.addProperty("status", "queued");
                    obj.addProperty("position", queued.position());
                    obj.addProperty("id", queued.id());
                    obj.add("beatmap", GSON.toJsonTree(score.getBeatmap()));
                    obj.add("scores", router.getScoresArr(List.of(score)));

                    if (!Double.isNaN(start)) obj.addProperty("start", start);
                    if (!Double.isNaN(end)) obj.addProperty("end", end);

                    context.status(202).result(new Response(true, "Replay render queued!", obj).toString());
                });
    }

    private CompletableFuture<Void> renderShowcaseForAsync(@NotNull Context context, LinkedList<Score> scores,
                                                            ReplayService.QqUploadRequest qqUpload) {
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

            List<CompletableFuture<ReplayService.ReplayInput>> replayFutures = scores.stream()
                    .map(score -> router.replayController.getReplayFuture(score.getId())
                            .thenApply(path -> path == null ? null : new ReplayService.ReplayInput(
                                    ScoreId.renderAssetId(score.getId()), path)))
                    .toList();

            return CompletableFuture.allOf(replayFutures.toArray(new CompletableFuture[0]))
                    .thenAccept(_ -> {
                        LinkedList<ReplayService.ReplayInput> replays = replayFutures.stream()
                                .map(CompletableFuture::join)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toCollection(LinkedList::new));

                        final Path beatmapset;
                        try {
                            beatmapset = CacheService.getBeatmapsetArchivePath(beatmapsetId);
                        } catch (IOException e) {
                            throw new ApiException(ErrorCode.BEATMAPSET_FETCH_FAILED,
                                    "Failed to prepare beatmapset for osuRenderer", e);
                        }
                        final ReplayService.QueuedJob queued = replayService.queueRenderShowcase(
                                String.valueOf(beatmapId), beatmapsetId, replays, beatmapset, qqUpload);

                        JsonObject obj = new JsonObject();
                        obj.addProperty("status", "queued");
                        obj.addProperty("position", queued.position());
                        obj.addProperty("id", queued.id());
                        obj.add("beatmap", GSON.toJsonTree(beatmap));
                        obj.add("scores", router.getScoresArr(scores));

                        context.status(202).result(new Response(true, "Replay render queued!", obj).toString());
                    });
        });
    }

    public Path getReplay(long id) {
        return getReplayFuture(id).join();
    }

    public CompletableFuture<Path> getReplayFuture(long id) {
        final Optional<Path> replayCache = CacheService.getReplayCache(id);
        if (replayCache.isPresent()) {
            return CompletableFuture.completedFuture(replayCache.get());
        }

        if (ScoreId.isLocal(id)) {
            return CompletableFuture.failedFuture(
                    new ApiException(ErrorCode.REPLAY_UNAVAILABLE, "Local replay is unavailable: " + ScoreId.format(id))
            );
        }

        final CompletableFuture<Path> pending = new CompletableFuture<>();
        final CompletableFuture<Path> existing = replayFetches.putIfAbsent(id, pending);
        if (existing != null) {
            return existing;
        }

        executor.enqueueReplayAsync(() -> {
            final Optional<Path> cacheAfterQueue = CacheService.getReplayCache(id);
            if (cacheAfterQueue.isPresent()) {
                return cacheAfterQueue.get();
            }

            try {
                return CacheService.getReplayBlocking(tokenManager.getTokenData(), id);
            } catch (IOException e) {
                throw new ApiException(ErrorCode.REPLAY_FETCH_FAILED, "Failed to cache replay for score id: " + id, e);
            }
        }).whenComplete((path, error) -> {
            try {
                if (error == null) {
                    pending.complete(path);
                } else {
                    pending.completeExceptionally(error);
                }
            } finally {
                replayFetches.remove(id, pending);
            }
        });

        return pending;
    }

    public void uploadReplay(@NotNull Context context) {
        try {
            final byte[] bytes = context.bodyAsBytes();
            final OsuReplay osuReplay = ReplayParser.parseReplay(bytes);

            context.future(() -> executor.enqueueAsync(() -> storeUploadedReplay(osuReplay, bytes))
                    .thenAccept(uploaded -> {
                        Score score = uploaded.score();
                        JsonObject data = new JsonObject();
                        if (ScoreId.isLocal(score)) {
                            data.addProperty("scoreId", uploaded.id());
                        } else {
                            data.addProperty("scoreId", score.getId());
                        }
                        data.addProperty("beatmapId", score.getBeatmap().getId());
                        data.addProperty("beatmapsetId", score.getBeatmap().getBeatmapsetId());
                        data.addProperty("userId", score.getUserId());
                        data.addProperty("username", score.getUser().getUsername());
                        context.result(new Response(true, "Replay uploaded successfully!", data).toString());

                        LOG.debug("Replay uploaded successfully for score id {}", uploaded.id());
                    }));
        } catch (ParseException e) {
            throw new ApiException(ErrorCode.REPLAY_PARSE_FAILED, "Failed to parse replay", e);
        }
    }

    private UploadedReplay storeUploadedReplay(OsuReplay replay, byte[] bytes) {
        Score onlineScore = null;
        if (replay.replayInfo() != null && replay.replayInfo().onlineId() != null
                && replay.replayInfo().onlineId() > 0) {
            onlineScore = OsuAPI.getScore(tokenManager.getTokenData(), replay.replayInfo().onlineId());
        } else if (replay.legacyScoreId() > 0) {
            onlineScore = OsuAPI.getLegacyScore(tokenManager.getTokenData(), replay.legacyScoreId());
        }

        if (onlineScore != null) {
            try {
                CacheService.transferReplay(onlineScore.getId(), bytes);
            } catch (IOException e) {
                throw new ApiException(ErrorCode.REPLAY_UPLOAD_FAILED, "Failed to upload replay", e);
            }
            return new UploadedReplay(ScoreId.format(onlineScore), onlineScore);
        }

        LocalScoreService.StoredLocalScore local = localScoreService.store(bytes, replay);
        return new UploadedReplay(local.id(), local.score());
    }

    private record UploadedReplay(String id, Score score) {
    }

    private ReplayService.QqUploadRequest parseQqUpload(Context context) {
        if (context.body() == null || context.body().isBlank()) {
            return null;
        }
        try {
            JsonObject body = GSON.fromJson(context.body(), JsonObject.class);
            if (body == null || !body.has("qqUpload") || body.get("qqUpload").isJsonNull()) {
                return null;
            }
            return GSON.fromJson(body.get("qqUpload"), ReplayService.QqUploadRequest.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid qqUpload request", e);
        }
    }

    public record ShowcaseRequest(List<String> ids, ReplayService.QqUploadRequest qqUpload) {
    }

    public record PreviewRequest(String mods, ReplayService.QqUploadRequest qqUpload) {
    }
}
