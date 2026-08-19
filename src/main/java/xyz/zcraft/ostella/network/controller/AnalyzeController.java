package xyz.zcraft.ostella.network.controller;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import desu.life.RosuFFI;
import io.javalin.http.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import xyz.zcraft.ostella.data.ScoreId;
import xyz.zcraft.ostella.exception.ApiException;
import xyz.zcraft.ostella.network.ErrorCode;
import xyz.zcraft.ostella.network.PerfPlusApi;
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
import xyz.zcraft.osu.parser.*;
import xyz.zcraft.osu.parser.data.beatmap.DiffSpec;
import xyz.zcraft.osu.parser.data.beatmap.OsuBeatmap;
import xyz.zcraft.osu.parser.data.replay.HitEvent;
import xyz.zcraft.osu.parser.data.replay.OsuReplay;
import xyz.zcraft.osu.parser.data.replay.ReplayAnalyze;
import xyz.zcraft.osu.parser.data.replay.WdPerform;
import xyz.zcraft.osu.parser.exception.AnalyzeException;
import xyz.zcraft.osu.parser.exception.ParseException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static xyz.zcraft.ostella.util.RequestUtil.requirePathInt;
import static xyz.zcraft.ostella.util.RequestUtil.requirePathScoreId;

public class AnalyzeController {
    private static final Logger LOG = LogManager.getLogger(AnalyzeController.class);
    private static final int PERFORMANCE_GRAPH_MAX_POINTS = 120;

    final RenderService renderer;
    final AsyncService executor;
    final TokenManager tokenManager;
    final Router router;
    final PerfPlusApi perfPlusApi;

    public AnalyzeController(Router router) {
        this.router = router;
        this.tokenManager = router.tokenManager;
        this.renderer = router.renderer;
        this.executor = router.executor;
        this.perfPlusApi = new PerfPlusApi(router.conf.performancePlus().endpoint());
    }

    private static PerformanceGraphData buildPerformanceGraphData(OsuBeatmap beatmap,
                                                                  ReplayAnalyze analyze,
                                                                  Score score)
            throws AnalyzeException {
        if (beatmap.getHitObjects() == null || beatmap.getHitObjects().isEmpty()) {
            throw new AnalyzeException("No hit objects found in the beatmap.");
        }

        final long firstObjectTime = beatmap.getHitObjects().getFirst().getTime();
        final long lastObjectTime = beatmap.getHitObjects().getLast().getTime();
        final long mapSpan = Math.max(1, lastObjectTime - firstObjectTime);
        final long windowDurationMs = Math.max(3_000, mapSpan / 50);

        final List<double[]> windowDifficulties = new ArrayList<>();
        final int sampleStep = Math.max(1, (int) Math.ceil(
                beatmap.getHitObjects().size() / (double) PERFORMANCE_GRAPH_MAX_POINTS));
        for (int objectIndex = 0; objectIndex < beatmap.getHitObjects().size(); objectIndex += sampleStep) {
            final long windowStart = beatmap.getHitObjects().get(objectIndex).getTime();
            final long windowEnd = windowStart + windowDurationMs;
            if (windowEnd > lastObjectTime) break;

            windowDifficulties.add(calculatePerformancePoint(beatmap, windowStart, windowEnd));
        }

        if (windowDifficulties.isEmpty()) {
            windowDifficulties.add(calculatePerformancePoint(beatmap, firstObjectTime,
                    Math.max(firstObjectTime + 1, lastObjectTime)));
        }

        final String mods = score.getMods().stream()
                .map(Mod::getAcronym)
                .reduce("", String::concat);
        final List<double[]> realtimePp = calculateRealtimePp(
                beatmap, analyze, mods, score.getMaxCombo());

        final List<Long> misses = objectResultTimes(analyze.events(), HitEvent.HitResult.MISS);
        final List<Long> hit50s = objectResultTimes(analyze.events(), HitEvent.HitResult.MEH);
        final List<Long> hit100s = objectResultTimes(analyze.events(), HitEvent.HitResult.OK);
        final List<Long> sliderTickBreaks = analyze.events().stream()
                .filter(event -> !event.wasHit())
                .filter(event -> event.eventType() == HitEvent.EventType.SLIDER_TICK)
                .map(HitEvent::eventTime)
                .toList();
        final List<Long> sliderEndBreaks = analyze.events().stream()
                .filter(event -> !event.wasHit())
                .filter(event -> event.eventType() == HitEvent.EventType.SLIDER_END)
                .map(HitEvent::eventTime)
                .toList();

        return new PerformanceGraphData(windowDifficulties, realtimePp, misses, hit50s, hit100s,
                sliderTickBreaks, sliderEndBreaks, lastObjectTime);
    }

    static List<double[]> calculateRealtimePp(OsuBeatmap beatmap,
                                               ReplayAnalyze analyze,
                                               String mods,
                                               Long finalMaxCombo)
            throws AnalyzeException {
        final List<HitEvent> events = analyze.events();
        final List<double[]> realtimePp = new ArrayList<>();
        final int objectCount = beatmap.getHitObjects().size();
        final int sampleStep = Math.max(1, (int) Math.ceil(
                objectCount / (double) PERFORMANCE_GRAPH_MAX_POINTS));

        int eventIndex = 0;
        int n300 = 0;
        int n100 = 0;
        int n50 = 0;
        int misses = 0;
        int currentCombo = 0;
        int maxCombo = 0;

        try (var rosuBeatmap = new RosuFFI.Beatmap(beatmap.toBeatmapString().getBytes());
             var rosuMods = RosuFFI.Mods.fromAcronyms(mods, RosuFFI.Mode.Osu);
             var performance = new RosuFFI.Performance()) {
            performance.mods(rosuMods);

            for (int objectIndex = 0; objectIndex < objectCount; objectIndex++) {
                while (eventIndex < events.size()
                        && events.get(eventIndex).objectIndex() <= objectIndex) {
                    final HitEvent event = events.get(eventIndex++);
                    if (event.objectIndex() < objectIndex) continue;

                    if (event.isObjectStart()) {
                        switch (event.hitResult()) {
                            case PERFECT -> n300++;
                            case OK -> n100++;
                            case MEH -> n50++;
                            case MISS -> misses++;
                        }
                    }

                    if (isComboEvent(event)) {
                        if (event.wasHit()) {
                            currentCombo++;
                            maxCombo = Math.max(maxCombo, currentCombo);
                        } else {
                            currentCombo = 0;
                        }
                    }
                }

                final boolean lastObject = objectIndex == objectCount - 1;
                if (objectIndex % sampleStep != 0 && !lastObject) continue;

                performance.passedObjects(objectIndex + 1L);
                performance.n300(n300);
                performance.n100(n100);
                performance.n50(n50);
                performance.misses(misses);
                performance.combo(lastObject && finalMaxCombo != null ? finalMaxCombo : maxCombo);

                final double pp = performance.calculate(rosuBeatmap).asOsu().pp;
                realtimePp.add(new double[]{
                        beatmap.getHitObjects().get(objectIndex).getTime(), pp
                });
            }
        } catch (RuntimeException e) {
            throw new AnalyzeException("Failed to calculate realtime PP", e);
        }

        return realtimePp;
    }

    private static boolean isComboEvent(HitEvent event) {
        return switch (event.eventType()) {
            case HIT_CIRCLE, SLIDER_HEAD, SLIDER_TICK, SLIDER_END, SPINNER -> true;
            case SPINNER_SPIN, SPINNER_BONUS -> false;
        };
    }

    private static double[] calculatePerformancePoint(OsuBeatmap beatmap, long start, long end)
            throws AnalyzeException {
        try {
            final var difficulty = BeatmapAnalyzer.calculateWindowDifficulty(beatmap, start, end);
            return new double[]{start + (end - start) / 2.0, difficulty.getValue()};
        } catch (RuntimeException e) {
            throw new AnalyzeException("Failed to calculate window difficulty around " + start, e);
        }
    }

    private static List<Long> objectResultTimes(List<HitEvent> events,
                                                HitEvent.HitResult result) {
        return events.stream()
                .filter(HitEvent::isObjectStart)
                .filter(event -> event.hitResult() == result)
                .map(HitEvent::eventTime)
                .toList();
    }

    public void renderScoreAnalysisById(@NotNull Context context) {
        final long scoreId = requirePathScoreId(context, "scoreId");
        context.future(() -> router.getScore(scoreId)
                .thenCompose(score -> {
                    if (score == null) throw new ApiException(ErrorCode.NO_SCORE_FOUND);
                    final BeatmapExtended beatmap = score.getBeatmap();

                    context.header("X-Beatmap-Id", String.valueOf(beatmap.getId()))
                            .header("X-Score-Id", ScoreId.format(score));

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
                        osuReplay = ReplayParser.parseReplay(router.replayController.getReplay(scoreId));
                    } catch (ParseException e) {
                        throw new ApiException(ErrorCode.REPLAY_PARSE_FAILED, e);
                    }

                    final ReplayAnalyze analyze;
                    try {
                        analyze = ReplayAnalyzer.analyze(osuBeatmap, osuReplay);
                    } catch (ParseException e) {
                        throw new ApiException(ErrorCode.REPLAY_PARSE_FAILED, e);
                    }

                    final List<Long> hitErrors = analyze.events().stream()
                            .filter(HitEvent::wasHit)
                            .filter(e -> e.eventType() == HitEvent.EventType.HIT_CIRCLE || e.eventType() == HitEvent.EventType.SLIDER_HEAD)
                            .map(HitEvent::hitTimeOffset)
                            .toList();

                    final List<HitEvent.AimBias> aimBiasAbs = analyze.events().stream()
                            .filter(HitEvent::wasHit)
                            .filter(e -> e.eventType() == HitEvent.EventType.HIT_CIRCLE || e.eventType() == HitEvent.EventType.SLIDER_HEAD)
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
                            .filter(hitEvent -> hitEvent.hitResult() == HitEvent.HitResult.MISS)
                            .filter(e -> e.eventType() == HitEvent.EventType.HIT_CIRCLE || e.eventType() == HitEvent.EventType.SLIDER_HEAD)
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
                    final PerformanceGraphData performanceGraph;
                    try {
                        performanceGraph = buildPerformanceGraphData(osuBeatmap, analyze, score);
                    } catch (AnalyzeException e) {
                        throw new ApiException(ErrorCode.SCORE_PARSE_FAILED, e);
                    }

                    return perfPlusApi.calculate(score)
                            .exceptionally(error -> {
                                LOG.warn("Could not calculate performance+ for score {}. "
                                                + "Rendering analysis without its skill breakdown.",
                                        ScoreId.format(score), error);
                                return null;
                            })
                            .thenApply(performancePlus -> new ScoreAnalyzeData(
                                    score, diffSpec, hitErrors, hitPos, hitPosAbs,
                                    missPos, missPosAbs, aimBias, avgTimingError, analyze,
                                    performanceGraph, performancePlus));
                })
                .thenApplyAsync(renderer::renderScoreAnalysis, renderer.getRenderExecutor())
                .thenAccept(bytes -> context.status(200).result(bytes)));
    }

    public void getMisses(@NotNull Context context) {
        final long scoreId = requirePathScoreId(context, "scoreId");
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
        final long scoreId = requirePathScoreId(context, "scoreId");
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
                .header("X-Score-Id", ScoreId.format(score));

        final Path rosuPath = CacheService.getBeatmapPath(beatmap.getId());

        router.ensurePp(score);

        try {
            final OsuBeatmap osuBeatmap = BeatmapParser.parseBeatmap(rosuPath);
            final Path replay = router.replayController.getReplay(score.getId());
            final OsuReplay osuReplay = ReplayParser.parseReplay(replay);

            return ReplayAnalyzer.analyze(osuBeatmap, osuReplay);
        } catch (ParseException e) {
            throw new ApiException(ErrorCode.BEATMAP_PARSE_FAILED, e);
        }
    }

    public void visualizeMiss(@NotNull Context context) {
        final long scoreId = requirePathScoreId(context, "scoreId");
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
            ReplayAnalyze replayAnalyze,
            PerformanceGraphData performanceGraph,
            PerfPlusApi.PerformancePlus performancePlus
    ) {
    }

    public record PerformanceGraphData(
            List<double[]> windowDifficulties,
            List<double[]> realtimePp,
            List<Long> misses,
            List<Long> hit50s,
            List<Long> hit100s,
            List<Long> sliderTickBreaks,
            List<Long> sliderEndBreaks,
            long mapEndTime
    ) {
    }
}
