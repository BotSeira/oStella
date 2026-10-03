package xyz.zcraft.ostella.network.controller;

import com.google.gson.Gson;
import io.javalin.http.Context;
import xyz.zcraft.ostella.data.ScoreType;
import xyz.zcraft.ostella.exception.ApiException;
import xyz.zcraft.ostella.network.ErrorCode;
import xyz.zcraft.ostella.network.OsuAPI;
import xyz.zcraft.ostella.network.Response;
import xyz.zcraft.ostella.network.Router;
import xyz.zcraft.ostella.service.CacheService;
import xyz.zcraft.osu.model.Beatmap;
import xyz.zcraft.osu.model.Mod;
import xyz.zcraft.osu.model.Score;
import xyz.zcraft.osu.parser.BeatmapParser;
import xyz.zcraft.osu.parser.OsuParser;

import java.time.Instant;
import java.util.*;

/**
 * JSON-only inputs for Seira's group challenges; never needs a replay or image render.
 */
public final class ChallengeController {
    private static final Set<String> ALLOWED_MODS = Set.of(
            "NF", "EZ", "HD", "HR", "SD", "DT", "NC", "HT", "FL", "SO", "PF", "CL", "MR");
    private final Router router;
    private final Gson gson = new Gson();

    public ChallengeController(Router router) {
        this.router = router;
    }

    public static SkillData estimateSkill(List<Score> scores, long excludedSet, String username,
                                          java.util.function.ToDoubleFunction<Score> difficulty) {
        List<Double> stars = new ArrayList<>();
        Set<Long> maps = new HashSet<>();
        for (Score score : scores) {
            if (stars.size() == 20) break;
            if (rejection(score) != null || score.getBeatmap() == null
                    || Objects.equals(score.getBeatmap().getBeatmapsetId(), excludedSet)
                    || score.getPp() == null || score.getPp() <= 0
                    || !maps.add(score.getBeatmap().getId())) continue;
            double rating = difficulty.applyAsDouble(score);
            if (!Double.isFinite(rating) || rating <= 0) throw new IllegalArgumentException("无法估计水平：星数无效。");
            stars.add(rating);
        }
        if (stars.size() < 5)
            throw new IllegalArgumentException("至少需要 5 张其他谱面的有效 osu!standard BP 才能估计水平。");
        stars.sort(Double::compareTo);
        int middle = stars.size() / 2;
        double median = stars.size() % 2 == 0 ? (stars.get(middle - 1) + stars.get(middle)) / 2 : stars.get(middle);
        return new SkillData(username, median, stars.size());
    }

    public static String rejection(Score score) {
        if (!Boolean.TRUE.equals(score.getPassed()) || !Objects.equals(score.getRulesetId(), 0L))
            return "只接受通过的 osu!standard 在线成绩。";
        if (score.getBeatmap() == null || !ranked(score.getBeatmap().getStatus()))
            return "只接受 ranked/approved 谱面。";
        if (score.getTotalScore() == null || score.getTotalScore() <= 0
                || score.getAccuracy() == null || !Double.isFinite(score.getAccuracy())
                || score.getAccuracy() < 0 || score.getAccuracy() > 1 || score.getMaxCombo() == null)
            return "成绩数据尚不完整，请稍后重试。";
        for (Mod mod : score.getMods() == null ? List.<Mod>of() : score.getMods()) {
            if (mod == null || !ALLOWED_MODS.contains(mod.getAcronym())) return "挑战不接受辅助、自动或自定义 Mod。";
            if (!defaultSettings(mod)) return "挑战不接受自定义 Mod 设置或自定义速率。";
        }
        return null;
    }

    private static boolean defaultSettings(Mod mod) {
        if (mod.getSettings() == null || mod.getSettings().isEmpty()) return true;
        double rate = switch (mod.getAcronym()) {
            case "DT", "NC" -> 1.5;
            case "HT" -> 0.75;
            default -> 1;
        };
        for (var setting : mod.getSettings().entrySet()) {
            if (Set.of("DT", "NC", "HT").contains(mod.getAcronym()) && "adjust_pitch".equals(setting.getKey()))
                continue;
            if (rate != 1 && "speed_change".equals(setting.getKey())
                    && setting.getValue() instanceof Number n && Math.abs(n.doubleValue() - rate) < 0.000001) continue;
            return false;
        }
        return true;
    }

    private static String modString(Score score) {
        return score.getMods() == null ? "" : score.getMods().stream().map(Mod::getAcronym).sorted().reduce("", String::concat);
    }

    private static boolean ranked(String status) {
        return "ranked".equals(status) || "approved".equals(status);
    }

    private static long positiveId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
        }
        throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, "ID 和 exclude_set 必须为正整数。");
    }

    public void beatmapset(Context ctx) {
        long id = positiveId(ctx.pathParam("beatmapsetId"));
        ctx.future(() -> router.executor.enqueueAsync(() -> {
            var set = OsuAPI.getBeatmapset(router.tokenManager.getTokenData(), id);
            return setData(set);
        }).thenAccept(data -> reply(ctx, data)).exceptionally(error -> fail(ctx, error)));
    }

    public void beatmap(Context ctx) {
        long id = positiveId(ctx.pathParam("beatmapId"));
        ctx.future(() -> router.executor.enqueueAsync(() -> setData(
                        OsuAPI.getBeatmapsetFromBeatmap(router.tokenManager.getTokenData(), id)))
                .thenAccept(data -> reply(ctx, data)).exceptionally(error -> fail(ctx, error)));
    }

    private SetData setData(xyz.zcraft.osu.model.Beatmapset set) {
        if (set == null || set.getBeatmaps() == null) throw new IllegalArgumentException("谱面集不存在。");
        var maps = set.getBeatmaps().stream()
                .filter(map -> "osu".equals(map.getMode()) && ranked(map.getStatus()))
                .filter(map -> map.getDifficultyRating() != null && map.getDifficultyRating() > 0)
                .sorted(Comparator.comparingDouble(Beatmap::getDifficultyRating))
                .map(map -> new MapChoice(map.getId(), map.getVersion(), map.getDifficultyRating())).toList();
        if (maps.isEmpty()) throw new IllegalArgumentException("挑战需要含 ranked/approved osu!standard 难度的谱面集。");
        return new SetData(set.getId(), set.getArtist() + " - " + set.getTitle(), maps);
    }

    public void skill(Context ctx) {
        long uid = positiveId(ctx.pathParam("userId"));
        long excludedSet = positiveId(ctx.queryParam("exclude_set"));
        ctx.future(() -> router.executor.enqueueAsync(() -> {
            var user = OsuAPI.getUser(router.tokenManager.getTokenData(), uid);
            if (user == null) throw new IllegalArgumentException("玩家不存在。");
            var scores = OsuAPI.getUserScores(router.tokenManager.getTokenData(), uid, ScoreType.BEST, 50);
            return estimateSkill(scores, excludedSet, user.getUsername(), this::effectiveStars);
        }).thenAccept(data -> reply(ctx, data)).exceptionally(error -> fail(ctx, error)));
    }

    public void score(Context ctx) {
        long id = positiveId(ctx.pathParam("scoreId"));
        ctx.future(() -> router.getScore(id).thenApply(score -> {
            if (score == null) throw new IllegalArgumentException("成绩不存在。");
            String reason = rejection(score);
            if (reason != null) return new ScoreData(id, 0, 0, 0, 0, 0, 0, 0, "", 0, reason);
            long endedAt;
            try {
                endedAt = Instant.parse(score.getEndedAt()).toEpochMilli();
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("成绩缺少有效的结束时间。");
            }
            return new ScoreData(score.getId(), score.getUserId(), score.getBeatmap().getId(),
                    score.getBeatmap().getBeatmapsetId(), score.getTotalScore(), effectiveStars(score),
                    score.getAccuracy(), score.getMaxCombo(), modString(score), endedAt, null);
        }).thenAccept(data -> reply(ctx, data)).exceptionally(error -> fail(ctx, error)));
    }

    private double effectiveStars(Score score) {
        try {
            var map = BeatmapParser.parseBeatmap(CacheService.getBeatmapPath(score.getBeatmap().getId()));
            double stars = OsuParser.getDiffSpecForMap(map, modString(score)).getStar();
            if (!Double.isFinite(stars) || stars <= 0) throw new IllegalArgumentException("无效星数。");
            return stars;
        } catch (Exception e) {
            throw new IllegalStateException("计算 Mod 后星数失败。", e);
        }
    }

    private void reply(Context ctx, Object data) {
        ctx.contentType("application/json").result(new Response(true, "Success", gson.toJsonTree(data)).toString());
    }

    private Void fail(Context ctx, Throwable error) {
        Throwable cause = error;
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null)
            cause = cause.getCause();
        if (!(cause instanceof IllegalArgumentException)) throw new java.util.concurrent.CompletionException(cause);
        ctx.status(400).contentType("application/json").result(new Response(false, cause.getMessage(), null).toString());
        return null;
    }

    public record MapChoice(long id, String name, double stars) {
    }

    public record SetData(long id, String title, List<MapChoice> maps) {
    }

    public record SkillData(String username, double stars, int samples) {
    }

    public record ScoreData(long id, long userId, long beatmapId, long beatmapsetId, long totalScore,
                            double stars, double accuracy, long maxCombo, String mods, long endedAt, String rejection) {
    }
}
