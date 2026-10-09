package xyz.zcraft.ostella.network.controller;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import io.javalin.http.Context;
import xyz.zcraft.ostella.addpp.BpProjection;
import xyz.zcraft.ostella.addpp.MapConditions;
import xyz.zcraft.ostella.addpp.MapPpCalculator;
import xyz.zcraft.ostella.data.ScoreType;
import xyz.zcraft.ostella.exception.ApiException;
import xyz.zcraft.ostella.network.ErrorCode;
import xyz.zcraft.ostella.network.OsuAPI;
import xyz.zcraft.ostella.network.Response;
import xyz.zcraft.ostella.network.Router;
import xyz.zcraft.ostella.service.CacheService;
import xyz.zcraft.osu.model.BeatmapExtended;

import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * Read-only hypothetical BP update. Does not submit scores or change account data.
 */
public final class AddPpController {
    private final Router router;
    private final Gson gson = new Gson();

    public AddPpController(Router router) {
        this.router = router;
    }

    public void addPp(Context ctx) {
        long uid;
        Request request;
        MapConditions conditions;
        try {
            uid = Long.parseLong(ctx.pathParam("userId"));
            if (uid <= 0) throw new IllegalArgumentException("玩家 ID 必须为正整数。");
            if (ctx.body().length() > 4096) throw new IllegalArgumentException("请求内容过长。");
            request = gson.fromJson(ctx.body(), Request.class);
            if (request == null) throw new IllegalArgumentException("缺少估算参数。");
            conditions = request.validate();
        } catch (IllegalArgumentException | JsonParseException e) {
            badRequest(ctx, e.getMessage());
            return;
        }
        var calculation = request.beatmapId() == null ? java.util.concurrent.CompletableFuture.completedFuture(
                new Calculated(request.pp(), null)) : router.executor.enqueueAsync(() -> calculateMap(request.beatmapId(), conditions));
        // Invalid map conditions are resolved before fetching the user's profile and BP window.
        ctx.future(() -> calculation.thenCompose(calculated -> {
                    var user = router.executor.enqueueAsync(() -> OsuAPI.getUser(router.tokenManager.getTokenData(), uid));
                    var scores = router.executor.enqueueAsync(() -> OsuAPI.getUserScores(router.tokenManager.getTokenData(), uid,
                            ScoreType.BEST, OsuAPI.MAX_USER_SCORES_LIMIT));
                    return user.thenCombine(scores, (profile, best) -> {
                        if (profile == null) throw new ApiException(ErrorCode.NO_USER_FOUND);
                        if (profile.getStatistics() == null || profile.getStatistics().getPp() == null)
                            throw new IllegalStateException("玩家 PP 数据不完整，请稍后重试。");
                        var plays = best.stream().map(score -> {
                            if (score.getBeatmap() == null || score.getBeatmap().getId() == null || score.getPp() == null)
                                throw new IllegalStateException("BP 数据不完整，请稍后重试。");
                            return new BpProjection.Play(score.getBeatmap().getId(), score.getPp());
                        }).toList();
                        int count = request.count() == null ? 1 : request.count();
                        var projected = BpProjection.project(profile.getStatistics().getPp(), plays, calculated.pp(), count,
                                request.beatmapId(), OsuAPI.MAX_USER_SCORES_LIMIT);
                        return new Result(profile.getUsername(), uid, profile.getStatistics().getGlobalRank(),
                                projected.before(), projected.after(), projected.change(), calculated.pp(), count, plays.size(),
                                projected.positions(), projected.replaced(), calculated.map(),
                                xyz.zcraft.ostella.whatif.WhatIfEstimates.project(router.whatIf.current(),
                                        projected.before(), projected.after(), profile.getStatistics().getGlobalRank(), java.time.Instant.now()));
                    });
                }).thenAccept(result -> ctx.contentType("application/json")
                        .result(new Response(true, "Success", gson.toJsonTree(result)).toString()))
                .exceptionally(error -> {
                    Throwable cause = error;
                    while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                    if (cause instanceof IllegalArgumentException) {
                        badRequest(ctx, cause.getMessage());
                        return null;
                    }
                    throw new CompletionException(cause);
                }));
    }

    private Calculated calculateMap(long id, MapConditions conditions) {
        BeatmapExtended map = OsuAPI.getBeatmap(router.tokenManager.getTokenData(), id);
        if (map == null) throw new ApiException(ErrorCode.NO_BEATMAP_FOUND);
        if (!"osu".equals(map.getMode()) || !("ranked".equals(map.getStatus()) || "approved".equals(map.getStatus())))
            throw new IllegalArgumentException("只有 ranked/approved 的 osu!standard 原生谱面能用于账号 PP 估算。");
        var result = MapPpCalculator.calculate(CacheService.getBeatmapPath(id), conditions);
        String title = map.getBeatmapset() == null ? "m" + id
                : map.getBeatmapset().getArtist() + " - " + map.getBeatmapset().getTitle();
        return new Calculated(result.pp(), new MapResult(id, title, map.getVersion(), conditions.mods(),
                result.stars(), result.maxCombo(), result.hits()));
    }

    private void badRequest(Context ctx, String message) {
        ctx.status(400).contentType("application/json").result(new Response(false, message, null).toString());
    }

    public record Request(Double pp, Integer count, Long beatmapId, List<String> conditions) {
        public MapConditions validate() {
            if ((pp == null) == (beatmapId == null))
                throw new IllegalArgumentException("请选择 PP 数值或谱面，不能同时指定。");
            if (beatmapId != null) {
                if (beatmapId <= 0 || (count != null && count != 1))
                    throw new IllegalArgumentException("谱面 ID 必须为正整数；谱面估算只支持一条成绩。");
                return MapConditions.parse(conditions);
            }
            if (!Double.isFinite(pp) || pp <= 0 || pp > 100_000 || count == null || count < 1 || count > 100
                    || (conditions != null && !conditions.isEmpty()))
                throw new IllegalArgumentException("单条 PP 必须为 0–100000 之间的正数，条数为 1–100。");
            return null;
        }
    }

    public record MapResult(long id, String title, String difficulty, String mods, double stars,
                            int maxCombo, MapConditions.Resolved hits) {
    }

    public record Result(String username, long userId, Long rank, double beforePp, double afterPp, double change,
                         double scorePp, int count, int sampled, List<Integer> positions, boolean replaced,
                         MapResult map, xyz.zcraft.ostella.whatif.WhatIfEstimates.RankProjection rankProjection) {
    }

    private record Calculated(double pp, MapResult map) {
    }
}
