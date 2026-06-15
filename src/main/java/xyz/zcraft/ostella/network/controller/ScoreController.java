package xyz.zcraft.ostella.network.controller;

import com.google.gson.JsonObject;
import io.javalin.http.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.zcraft.ostella.network.*;
import xyz.zcraft.ostella.service.AsyncService;
import xyz.zcraft.ostella.service.CacheService;
import xyz.zcraft.ostella.service.RenderService;
import xyz.zcraft.ostella.util.TokenManager;
import xyz.zcraft.osu.model.BeatmapExtended;
import xyz.zcraft.osu.model.Mod;
import xyz.zcraft.osu.model.Score;
import xyz.zcraft.osu.parser.BeatmapParser;
import xyz.zcraft.osu.parser.OsuParser;
import xyz.zcraft.osu.parser.data.beatmap.DiffSpec;
import xyz.zcraft.osu.parser.data.beatmap.OsuBeatmap;
import xyz.zcraft.osu.parser.exception.AnalyzeException;
import xyz.zcraft.osu.parser.exception.ParseException;

import static xyz.zcraft.ostella.util.RequestUtil.requireLong;
import static xyz.zcraft.ostella.util.RequestUtil.requirePathLong;

public class ScoreController {
    private static final Logger LOG = LogManager.getLogger(ScoreController.class);
    public final RenderService renderer;
    public final AsyncService executor;
    public final TokenManager tokenManager;
    public final Router router;

    public ScoreController(Router router) {
        this.router = router;
        this.renderer = router.renderer;
        this.executor = router.executor;
        this.tokenManager = router.tokenManager;
    }

    public void lookupScore(@NotNull Context context) {
        if (context.queryParam("of") != null) {
            lookupScoreOfRefAsync(context);
        } else if (context.queryParam("m") != null) {
            lookupScoreOfBeatmapAsync(context);
        } else if (context.queryParam("ms") != null) {
            lookupScoreOfBeatmapsetAsync(context);
        } else {
            lookupScoreOfIdAsync(context);
        }
    }

    public void renderScoreById(@NotNull Context context) {
        final long scoreId = requirePathLong(context, "scoreId");
        context.future(() -> router.getScore(scoreId)
                .thenApplyAsync(score -> {
                    if (score == null) throw new ApiException(ErrorCode.NO_SCORE_FOUND);
                    final BeatmapExtended beatmap = score.getBeatmap();

                    context.header("X-Beatmap-Id", String.valueOf(beatmap.getId()))
                            .header("X-Score-Id", String.valueOf(score.getId()));

                    try {
                        final OsuBeatmap osuBeatmap = BeatmapParser.parseBeatmap(CacheService.getBeatmapPath(beatmap.getId()));
                        final DiffSpec diffSpec = OsuParser.getDiffSpecForMap(osuBeatmap, score.getMods().stream().map(Mod::getAcronym).reduce("", String::concat));

                        Double calPp = null;
                        try {
                            calPp = OsuParser.estimatePp(score, osuBeatmap);
                        } catch (AnalyzeException e) {
                            LOG.error("Failed to estimate pp for score id: {}", score.getId(), e);
                        }

                        return renderer.renderScore(score, diffSpec, calPp);
                    } catch (ParseException e) {
                        throw new ApiException(ErrorCode.BEATMAP_PARSE_FAILED, e);
                    } catch (AnalyzeException e) {
                        throw new ApiException(ErrorCode.SCORE_PARSE_FAILED, e);
                    }
                }, renderer.getRenderExecutor())
                .thenAccept(bytes -> context.status(200).result(bytes)));
    }

    private void lookupScoreOfIdAsync(@NotNull Context context) {
        final long scoreId = requireLong(context, "s");
        context.future(() -> router.getScore(scoreId)
                .thenAccept(score -> context.status(200).result(
                        new Response(true, "Success", scoreLookupData(score)).toString()
                )));
    }

    private void lookupScoreOfBeatmapAsync(@NotNull Context context) {
        final long m = requireLong(context, "m");
        final long u = requireLong(context, "u");

        context.future(() -> executor.enqueueAsync(() -> OsuAPI.getUserScore(tokenManager.getTokenData(), u, m))
                .thenCompose(score -> {
                            if (score == null) {
                                throw new ApiException(ErrorCode.NO_SCORE_FOUND);
                            }

                            context.header("X-Score-Id", String.valueOf(score.getId()));
                            return executor
                                    .enqueueAsync(() -> OsuAPI.getBeatmapset(tokenManager.getTokenData(), score.getBeatmap().getBeatmapsetId()))
                                    .thenApply(beatmapset -> {
                                        score.getBeatmap().setBeatmapset(beatmapset);
                                        score.setBeatmapset(beatmapset);
                                        return score;
                                    });
                        }
                )
                .thenAccept(score -> context.status(200).result(
                        new Response(true, "Success", scoreLookupData(score)).toString()
                )));
    }

    private void lookupScoreOfRefAsync(@NotNull Context context) {
        context.future(() -> router.getScoreFromRefAsync(context)
                .thenApply(Score::getId)
                .thenCompose(router::getScore)
                .thenAccept(score -> context.status(200).result(
                        new Response(true, "Success", scoreLookupData(score)).toString()
                )));
    }

    private JsonObject scoreLookupData(Score score) {
        if (score == null) throw new ApiException(ErrorCode.NO_SCORE_FOUND);

        final JsonObject data = new JsonObject();
        data.addProperty("score_id", score.getId());

        if (score.getBeatmap() != null) {
            data.addProperty("beatmap_id", score.getBeatmap().getId());
            data.addProperty("beatmapset_id", score.getBeatmap().getBeatmapsetId());
        }

        if (score.getBeatmapset() != null) {
            data.addProperty("beatmapset_id", score.getBeatmapset().getId());
        }

        return data;
    }


    private void lookupScoreOfBeatmapsetAsync(@NotNull Context context) {
        context.future(() -> router.getScoreFromBeatmapsetAsync(context)
                .thenAccept(score -> context.status(200).result(
                        new Response(true, "Success", scoreLookupData(score)).toString()
                )));
    }
}
