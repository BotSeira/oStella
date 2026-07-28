package xyz.zcraft.ostella.network.controller;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.javalin.http.Context;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import xyz.zcraft.ostella.network.ApiException;
import xyz.zcraft.ostella.network.ErrorCode;
import xyz.zcraft.ostella.network.Response;
import xyz.zcraft.ostella.network.Router;
import xyz.zcraft.ostella.service.AsyncService;
import xyz.zcraft.ostella.service.CacheService;
import xyz.zcraft.ostella.service.MissVisualizeService;
import xyz.zcraft.ostella.service.RenderService;
import xyz.zcraft.ostella.util.TokenManager;
import xyz.zcraft.osu.model.BeatmapExtended;
import xyz.zcraft.osu.model.Mod;
import xyz.zcraft.osu.model.Score;
import xyz.zcraft.osu.parser.BeatmapParser;
import xyz.zcraft.osu.parser.OsuParser;
import xyz.zcraft.osu.parser.ReplayAnalyzer;
import xyz.zcraft.osu.parser.ReplayParser;
import xyz.zcraft.osu.parser.data.beatmap.DiffSpec;
import xyz.zcraft.osu.parser.data.beatmap.HitObject;
import xyz.zcraft.osu.parser.data.beatmap.OsuBeatmap;
import xyz.zcraft.osu.parser.data.replay.HitEvent;
import xyz.zcraft.osu.parser.data.replay.OsuReplay;
import xyz.zcraft.osu.parser.data.replay.ReplayAnalyze;
import xyz.zcraft.osu.parser.data.replay.WdPerform;
import xyz.zcraft.osu.parser.exception.AnalyzeException;
import xyz.zcraft.osu.parser.exception.ParseException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static xyz.zcraft.ostella.util.RequestUtil.requirePathInt;
import static xyz.zcraft.ostella.util.RequestUtil.requirePathLong;

public class AnalyzeController {
    final RenderService renderer;
    final AsyncService executor;
    final TokenManager tokenManager;
    final Router router;

    public AnalyzeController(Router router) {
        this.router = router;
        this.tokenManager = router.tokenManager;
        this.renderer = router.renderer;
        this.executor = router.executor;
    }

    public void renderScoreAnalysisById(@NotNull Context context) {
        final long scoreId = requirePathLong(context, "scoreId");
        context.future(() -> router.getScore(scoreId)
                .thenApply(score -> {
                    if (score == null) throw new ApiException(ErrorCode.NO_SCORE_FOUND);
                    final BeatmapExtended beatmap = score.getBeatmap();

                    context.header("X-Beatmap-Id", String.valueOf(beatmap.getId()))
                            .header("X-Score-Id", String.valueOf(score.getId()));

                    final OsuBeatmap osuBeatmap;
                    final DiffSpec diffSpec;
                    try {
                        osuBeatmap = BeatmapParser.parseBeatmap(CacheService.getBeatmapPath(beatmap.getId()));
                        diffSpec = OsuParser.getDiffSpecForMap(osuBeatmap, score.getMods().stream().map(Mod::getAcronym).reduce("", String::concat));

                        router.ensurePp(score, osuBeatmap);
                    } catch (ParseException e) {
                        throw new ApiException(ErrorCode.BEATMAP_PARSE_FAILED, e);
                    } catch (AnalyzeException e) {
                        throw new ApiException(ErrorCode.SCORE_PARSE_FAILED, e);
                    }

                    final OsuReplay osuReplay;
                    try {
                        osuReplay = ReplayParser.parseReplay(CacheService.getReplay(tokenManager.getTokenData(), score.getId()));
                    } catch (ParseException e) {
                        throw new ApiException(ErrorCode.REPLAY_PARSE_FAILED, e);
                    } catch (IOException e) {
                        throw new ApiException(ErrorCode.REPLAY_FETCH_FAILED, e);
                    }

                    final ReplayAnalyze analyze;
                    try {
                        analyze = ReplayAnalyzer.analyze(osuBeatmap, osuReplay);
                    } catch (ParseException e) {
                        throw new ApiException(ErrorCode.REPLAY_PARSE_FAILED, e);
                    }

                    final List<Long> hitErrors = analyze.events().stream()
                            .filter(HitEvent::wasHit)
                            .map(HitEvent::hitTimeOffset)
                            .toList();

                    final List<HitEvent.AimBias> aimBiasAbs = analyze.events().stream()
                            .filter(HitEvent::wasHit)
                            .filter(e -> e.hitObject().getObjectType() != HitObject.ObjectType.SPINNER)
                            .map(HitEvent::aimBias)
                            .filter(Objects::nonNull)
                            .toList();

                    final List<double[]> hitPos = aimBiasAbs.stream()
                            .map(HitEvent.AimBias::standardize)
                            .map(b -> new double[]{b.theta(), b.distance()})
                            .toList();

                    final List<double[]> hitPosAbs = aimBiasAbs.stream()
                            .map(b -> new double[]{b.theta(), b.distance()})
                            .toList();

                    final List<HitEvent.AimBias> misses = analyze.events().stream()
                            .filter(hitEvent -> !hitEvent.wasHit())
                            .filter(e -> e.hitObject().getObjectType() != HitObject.ObjectType.SPINNER)
                            .map(HitEvent::aimBias)
                            .filter(Objects::nonNull)
                            .filter(b -> b.distance() < diffSpec.getDifficulty().getCircleRadiusInPixel() * 1.2).toList();

                    final List<double[]> missPosAbs = misses.stream()
                            .map(b -> new double[]{b.theta(), b.distance()})
                            .toList();

                    final List<double[]> missPos = misses.stream()
                            .map(HitEvent.AimBias::standardize)
                            .map(b -> new double[]{b.theta(), b.distance()})
                            .toList();

                    final List<Double> aimBiases = aimBiasAbs.stream()
                            .map(HitEvent.AimBias::standardize)
                            .map(b -> b.distance() * (Math.abs(b.theta() - Math.PI) >= (Math.PI / 2) ? 1 : -1))
                            .toList();

                    final double aimBias = aimBiases.isEmpty() ? 0.0 : (aimBiases.stream().reduce(0.0, Double::sum) / aimBiases.size() / diffSpec.getDifficulty().getCircleRadiusInPixel());

                    final double avgTimingError = hitErrors.isEmpty() ? 0.0 : (hitErrors.stream().reduce(0L, Long::sum) / (double) hitErrors.size());
                    return new ScoreAnalyzeData(score, diffSpec, hitErrors, hitPos, hitPosAbs, missPos, missPosAbs, aimBias, avgTimingError, analyze);
                })
                .thenApplyAsync(renderer::renderScoreAnalysis, renderer.getRenderExecutor())
                .thenAccept(bytes -> context.status(200).result(bytes)));
    }

    public void getMisses(@NotNull Context context) {
        final long scoreId = requirePathLong(context, "scoreId");
        context.future(() -> router.getScore(scoreId)
                .thenApply(score -> getReplayAnalyze(context, score))
                .thenApply(analyze -> {
                    var misses = analyze.events().stream()
                            .filter(hitEvent -> !hitEvent.wasHit())
                            .filter(e -> e.eventType() == HitEvent.EventType.SLIDER_HEAD || e.eventType() == HitEvent.EventType.HIT_CIRCLE)
                            .toList();
                    return getMissArr(misses);
                })
                .thenAccept(arr -> context.status(200).result(new Response(true, "Success", arr).toString())));
    }

    private @NonNull JsonArray getMissArr(List<HitEvent> misses) {
        JsonArray arr = new JsonArray();
        for (int i = 0; i < misses.size(); i++) {
            JsonObject object = new JsonObject();
            object.addProperty("index", i + 1);
            object.addProperty("time", misses.get(i).hitObject().getTime());
            object.addProperty("type", misses.get(i).hitObject().getObjectType().name());
            arr.add(object);
        }
        return arr;
    }

    public void getScoreHighlight(@NotNull Context context) {
        final long scoreId = requirePathLong(context, "scoreId");
        context.future(() -> router.getScore(scoreId)
                .thenApply(score -> getReplayAnalyze(context, score))
                .thenApply(analyze -> {
                    final WdPerform highlight;
                    try {
                        highlight = OsuParser.getHighlight(analyze);
                    } catch (AnalyzeException e) {
                        throw new ApiException(ErrorCode.REPLAY_PARSE_FAILED, e);
                    }
                    JsonObject obj = new JsonObject();
                    obj.addProperty("start", highlight.startTime());
                    obj.addProperty("end", highlight.endTime());
                    obj.addProperty("pp", highlight.beatmapPp());
                    obj.addProperty("acc", highlight.accuracy());
                    obj.addProperty("score", highlight.wdScore());
                    return obj;
                })
                .thenAccept(obj -> context.status(200).result(new Response(true, "Success", obj).toString())));
    }

    private @NonNull ReplayAnalyze getReplayAnalyze(@NonNull Context context, Score score) {
        if (score == null) throw new ApiException(ErrorCode.NO_SCORE_FOUND);
        final BeatmapExtended beatmap = score.getBeatmap();

        context.header("X-Beatmap-Id", String.valueOf(beatmap.getId()))
                .header("X-Score-Id", String.valueOf(score.getId()));

        final Path rosuPath = CacheService.getBeatmapPath(beatmap.getId());

        router.ensurePp(score);

        try {
            final OsuBeatmap osuBeatmap = BeatmapParser.parseBeatmap(rosuPath);
            final Path replay = CacheService.getReplay(tokenManager.getTokenData(), score.getId());
            final OsuReplay osuReplay = ReplayParser.parseReplay(replay);

            return ReplayAnalyzer.analyze(osuBeatmap, osuReplay);
        } catch (ParseException e) {
            throw new ApiException(ErrorCode.BEATMAP_PARSE_FAILED, e);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.BEATMAP_FETCH_FAILED, e);
        }
    }

    public void visualizeMiss(@NotNull Context context) {
        final long scoreId = requirePathLong(context, "scoreId");
        final int missIndex = requirePathInt(context, "missIndex");
        context.future(() -> router.getScore(scoreId)
                .thenApply(score -> getReplayAnalyze(context, score))
                .thenApply(analyze -> MissVisualizeService.visualizeMiss(analyze, missIndex))
                .thenAccept(bytes -> context.status(200).result(bytes)));
    }

    public record ScoreAnalyzeData(
            Score score,
            DiffSpec diffSpec,
            List<Long> hitErrors,
            List<double[]> hitPositions,
            List<double[]> hitPositionsAbsolute,
            List<double[]> missPositions,
            List<double[]> missPositionsAbsolute,
            double aimBias,
            double avgTimingError,
            ReplayAnalyze replayAnalyze
    ) {
    }
}
