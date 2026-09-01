package xyz.zcraft.ostella.network.controller;

import com.google.gson.*;
import io.javalin.http.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.zcraft.ostella.data.ScoreFilter;
import xyz.zcraft.ostella.data.ScoreId;
import xyz.zcraft.ostella.data.ScoreType;
import xyz.zcraft.ostella.exception.ApiException;
import xyz.zcraft.ostella.network.ErrorCode;
import xyz.zcraft.ostella.network.OsuAPI;
import xyz.zcraft.ostella.network.Response;
import xyz.zcraft.ostella.network.Router;
import xyz.zcraft.ostella.service.AsyncService;
import xyz.zcraft.ostella.service.CacheService;
import xyz.zcraft.ostella.service.RenderService;
import xyz.zcraft.ostella.util.TokenManager;
import xyz.zcraft.ostella.util.WeightedRandom;
import xyz.zcraft.osu.model.BeatmapExtended;
import xyz.zcraft.osu.model.Mod;
import xyz.zcraft.osu.model.Score;
import xyz.zcraft.osu.model.UserExtended;
import xyz.zcraft.osu.parser.BeatmapParser;
import xyz.zcraft.osu.parser.BeatmapPatternAnalyzer;
import xyz.zcraft.osu.parser.OsuParser;
import xyz.zcraft.osu.parser.data.beatmap.BeatmapPatternAnalysis;
import xyz.zcraft.osu.parser.data.beatmap.DiffSpec;
import xyz.zcraft.osu.parser.data.beatmap.OsuBeatmap;
import xyz.zcraft.osu.parser.exception.AnalyzeException;
import xyz.zcraft.osu.parser.exception.ParseException;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static xyz.zcraft.ostella.util.RequestUtil.*;

public class ScoreController {
    private static final Logger LOG = LogManager.getLogger(ScoreController.class);
    private static final Gson GSON = new Gson();
    private static final Map<BeatmapPatternAnalysis.PatternType, Integer> PATTERN_WEIGHTS = Map.of(
            BeatmapPatternAnalysis.PatternType.TECH, 600,
            BeatmapPatternAnalysis.PatternType.READING, 500,
            BeatmapPatternAnalysis.PatternType.FLOW, 400,
            BeatmapPatternAnalysis.PatternType.STREAM, 300,
            BeatmapPatternAnalysis.PatternType.ALT, 300,
            BeatmapPatternAnalysis.PatternType.AIM, 1
    );
    public final RenderService renderer;
    public final AsyncService executor;
    public final TokenManager tokenManager;
    public final Router router;
    private final HashMap<Long, Integer> previousSelections = new HashMap<>();

    public ScoreController(Router router) {
        this.router = router;
        this.renderer = router.renderer;
        this.executor = router.executor;
        this.tokenManager = router.tokenManager;
    }

    private static List<ScoreFilter> requireScoreFilters(Context context) {
        try {
            return ScoreFilter.parseList(context.queryParam("filters"));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, e.getMessage(), e);
        }
    }

    static List<Score> applyFilters(List<Score> scores, List<ScoreFilter> filters) {
        if (filters.isEmpty()) {
            return scores;
        }
        return scores.stream()
                .filter(score -> filters.stream().allMatch(filter -> filter.matches(score)))
                .toList();
    }

    static int scoreLookupFetchLimit(int index, List<ScoreFilter> filters) {
        return filters.isEmpty() ? index : OsuAPI.MAX_USER_SCORES_LIMIT;
    }

    private static JsonObject mapScoreToJson(TargetScore targetScore) {
        final OsuBeatmap osuBeatmap;
        final DiffSpec diffSpec;

        final ScoreEntry entry = targetScore.entry();
        final Score score = entry.score();
        final UserExtended user = targetScore.user();

        try {
            osuBeatmap = BeatmapParser.parseBeatmap(CacheService.getBeatmapPath(score.getBeatmap().getId()));
            diffSpec = OsuParser.getDiffSpecForMap(osuBeatmap, score.getMods().stream().map(Mod::getAcronym).reduce("", String::concat));
        } catch (Exception e) {
            throw new ApiException(ErrorCode.BEATMAP_PARSE_FAILED, e);
        }

        JsonObject result = new JsonObject();
        result.add("user", GSON.toJsonTree(user));
        result.add("score", GSON.toJsonTree(score));
        result.addProperty("diff", "%.2f★ (CS %.2f / AR %.2f / OD %.2f / HP %.2f)".formatted(
                diffSpec.getStar(),
                diffSpec.getDifficulty().cs(),
                diffSpec.getDifficulty().ar(),
                diffSpec.getDifficulty().od(),
                diffSpec.getDifficulty().hp()
        ));
        result.addProperty("best_index", entry.bestIndex());
        result.addProperty("pp_weight", entry.score().getWeight().getPercentage());
        return result;
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
        final long scoreId = requirePathScoreId(context, "scoreId");

        context.future(() -> router.getScore(scoreId)
                .thenApplyAsync(score -> {
                    if (score == null) throw new ApiException(ErrorCode.NO_SCORE_FOUND);
                    final BeatmapExtended beatmap = score.getBeatmap();

                    context.header("X-Beatmap-Id", String.valueOf(beatmap.getId()))
                            .header("X-Score-Id", ScoreId.format(score));

                    try {
                        final OsuBeatmap osuBeatmap = BeatmapParser.parseBeatmap(CacheService.getBeatmapPath(beatmap.getId()));
                        final DiffSpec diffSpec = OsuParser.getDiffSpecForMap(osuBeatmap, score.getMods().stream().map(Mod::getAcronym).reduce("", String::concat));

                        Double calPp = null;
                        try {
                            calPp = OsuParser.estimatePp(score, osuBeatmap);
                        } catch (AnalyzeException e) {
                            LOG.error("Failed to estimate pp for score id: {}", score.getId(), e);
                        }

                        final boolean replayPresent = score.getHasReplay() || CacheService.hasReplayCache(score.getId());

                        return renderer.renderScore(score, diffSpec, calPp, replayPresent);
                    } catch (ParseException e) {
                        throw new ApiException(ErrorCode.BEATMAP_PARSE_FAILED, e);
                    } catch (AnalyzeException e) {
                        throw new ApiException(ErrorCode.SCORE_PARSE_FAILED, e);
                    }
                }, renderer.getRenderExecutor())
                .thenAccept(bytes -> context.status(200).result(bytes)));
    }

    private void lookupScoreOfIdAsync(@NotNull Context context) {
        final long scoreId = requireScoreId(context, "s");
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
        context.future(() -> getScoreFromRefAsync(context)
                .thenApply(Score::getId)
                .thenCompose(router::getScore)
                .thenAccept(score -> context.status(200).result(
                        new Response(true, "Success", scoreLookupData(score)).toString()
                )));
    }

    private JsonObject scoreLookupData(Score score) {
        if (score == null) throw new ApiException(ErrorCode.NO_SCORE_FOUND);

        final JsonObject data = new JsonObject();
        if (ScoreId.isLocal(score)) {
            data.addProperty("score_id", ScoreId.format(score));
        } else {
            data.addProperty("score_id", score.getId());
        }

        if (score.getBeatmap() != null) {
            data.addProperty("beatmap_id", score.getBeatmap().getId());
            data.addProperty("beatmapset_id", score.getBeatmap().getBeatmapsetId());
        }

        if (score.getBeatmapset() != null) {
            data.addProperty("beatmapset_id", score.getBeatmapset().getId());
        }

        return data;
    }

    public CompletableFuture<Score> getScoreFromBeatmapsetAsync(@NotNull Context context) {
        final long ms = requireLong(context, "ms");
        final int i = requireInt(context, "i");
        final long u = requireLong(context, "u");

        return executor.enqueueAsync(() -> OsuAPI.getBeatmapset(tokenManager.getTokenData(), ms))
                .thenCompose(beatmapset -> {
                    if (beatmapset == null) throw new ApiException(ErrorCode.NO_BEATMAPSET_FOUND);

                    final List<BeatmapExtended> beatmaps = beatmapset.getBeatmaps();

                    if (beatmaps.size() < i) throw new ApiException(ErrorCode.NO_BEATMAP_FOUND);

                    beatmaps.sort(Comparator.comparingDouble(BeatmapExtended::getDifficultyRating));
                    final BeatmapExtended beatmap = beatmaps.get(i - 1);
                    beatmap.setBeatmapset(beatmapset);
                    context.header("X-Beatmap-Id", String.valueOf(beatmap.getId()));
                    return executor
                            .enqueueAsync(() ->
                                    OsuAPI.getUserScore(tokenManager.getTokenData(), u, beatmap.getId())
                            )
                            .thenApply(score -> {
                                score.setBeatmap(beatmap);
                                score.setBeatmapset(beatmapset);
                                return score;
                            });
                });

    }

    private void lookupScoreOfBeatmapsetAsync(@NotNull Context context) {
        context.future(() -> getScoreFromBeatmapsetAsync(context)
                .thenAccept(score -> context.status(200).result(
                        new Response(true, "Success", scoreLookupData(score)).toString()
                )));
    }

    public CompletableFuture<Score> getScoreFromRefAsync(@NotNull Context context) {
        final String of = requireStringFrom(context, "of", "rs", "bo", "rp");
        final long u = requireLong(context, "u");
        final int i = requirePositiveInt(context, "i");
        final List<ScoreFilter> filters = requireScoreFilters(context);

        final ScoreType type = switch (of.toLowerCase()) {
            case "rs" -> ScoreType.RECENT;
            case "rp" -> ScoreType.RECENT_PASS;
            case "bo" -> ScoreType.BEST;
            default -> throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, "Invalid score type: " + of);
        };
        final int fetchLimit = scoreLookupFetchLimit(i, filters);

        return executor
                .enqueueAsync(() -> OsuAPI.getUserScores(tokenManager.getTokenData(), u, type, fetchLimit))
                .thenApply(scores -> {
                    if (!filters.isEmpty()) {
                        scores.forEach(router::ensurePp);
                    }
                    List<Score> filteredScores = applyFilters(scores, filters);
                    if (filteredScores.size() < i) {
                        throw new ApiException(ErrorCode.NO_SCORE_FOUND, "No scores found for user!");
                    }

                    return filteredScores.get(i - 1);
                });
    }

    public void randomScore(@NotNull Context context) {
        final long minRank = optionalLong(context, "min_rank", Long.MAX_VALUE);
        context.future(() ->
                executor.enqueueAsync(() -> OsuAPI.getLatestPassedScores(tokenManager.getTokenData()))
                        .thenApply(scores -> {
                            List<Long> userIds = scores.stream()
                                    .map(Score::getUserId)
                                    .distinct()
                                    .collect(Collectors.toCollection(ArrayList::new));

                            Collections.shuffle(userIds, ThreadLocalRandom.current());
                            return userIds;
                        })
                        .thenCompose(userIds -> findAvailableScore(userIds, 0, minRank))
                        .thenApply(ScoreController::mapScoreToJson)
                        .thenAccept(result ->
                                context.status(200).result(new Response(true, "Success", result).toString())
                        )
        );
    }

    public void randomScoreFromUsers(@NotNull Context context) {
        final JsonArray usersArray = JsonParser.parseString(context.body()).getAsJsonObject().get("uids").getAsJsonArray();
        if (usersArray == null || usersArray.isJsonNull() || usersArray.isEmpty()) {
            throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, "No users provided!");
        }

        final List<Long> userIds = new ArrayList<>();

        for (JsonElement element : usersArray) {
            userIds.add(element.getAsLong());
        }

        Collections.shuffle(userIds);

        context.future(() ->
                findAvailableScore(userIds, 0, Long.MAX_VALUE)
                        .thenApply(ScoreController::mapScoreToJson)
                        .thenAccept(result ->
                                context.status(200).result(new Response(true, "Success", result).toString())
                        )
        );
    }

    private CompletableFuture<TargetScore> findAvailableScore(List<Long> userIds, int index, long minRank) {
        if (index >= userIds.size()) {
            return CompletableFuture.failedFuture(
                    new ApiException(ErrorCode.NO_SCORE_FOUND, "No available scores found!")
            );
        }

        long userId = userIds.get(index);

        final int SCORE_LIMIT = 40;

        return executor.enqueueAsync(() ->
                OsuAPI.getUserScores(
                        tokenManager.getTokenData(),
                        userId,
                        ScoreType.BEST,
                        SCORE_LIMIT
                )
        ).thenCompose(scores -> {
            if (scores.size() < SCORE_LIMIT) {
                return findAvailableScore(userIds, index + 1, minRank);
            }

            List<ScoreEntry> candidates = new ArrayList<>(5);

            for (int i = 0; i < SCORE_LIMIT - 1; i++) {
                final Score score = scores.get(i);

                if (!score.getHasReplay() && !CacheService.hasReplayCache(score.getId())) {
                    continue;
                }

                candidates.add(new ScoreEntry(i + 1, score));
            }

            if (candidates.isEmpty()) {
                return findAvailableScore(userIds, index + 1, minRank);
            }

            return executor.enqueueAsync(() ->
                    OsuAPI.getUser(tokenManager.getTokenData(), userId)
            ).thenCompose(user -> {
                Long globalRank = user.getStatistics().getGlobalRank();

                if (globalRank == null || globalRank > minRank) {
                    return findAvailableScore(userIds, index + 1, minRank);
                }

                WeightedRandom<ScoreEntry> randomScores = new WeightedRandom<>();

                HashMap<ScoreEntry, Integer> baseWeights = new HashMap<>();

                for (ScoreEntry current : candidates) {
                    try {
                        baseWeights.put(current, getWeight(current));
                    } catch (ParseException e) {
                        LOG.warn("Failed to parse beatmap with id {}", current.score().getBeatmapId(), e);
                    }
                }

                if (baseWeights.isEmpty()) {
                    return findAvailableScore(userIds, index + 1, minRank);
                }

                final int maxWeight = baseWeights.values()
                        .stream()
                        .max(Integer::compareTo)
                        .get();

                for (Map.Entry<ScoreEntry, Integer> entry : baseWeights.entrySet()) {
                    final int selectedTimes = previousSelections.getOrDefault(entry.getKey().score().getId(), 0);

                    final double redundantFactor = Math.max(0.4, 1 - selectedTimes * 0.2);

                    final double normalizedWeight = (double) entry.getValue() / maxWeight;

                    final int finalWeight = Math.max(
                            1,
                            (int) (Math.pow(normalizedWeight, 5) * 1000 * redundantFactor)
                    );

                    randomScores.add(entry.getKey(), finalWeight);
                }

                ScoreEntry selected = randomScores.next();

                previousSelections.merge(
                        selected.score().getId(),
                        1,
                        Integer::sum
                );

                return CompletableFuture.completedFuture(new TargetScore(selected, user));
            });
        });
    }

    private int getWeight(ScoreEntry entry) throws ParseException {
        final Long beatmapId = entry.score().getBeatmapId();
        final OsuBeatmap osuBeatmap = BeatmapParser.parseBeatmap(CacheService.getBeatmapPath(beatmapId));
        final BeatmapPatternAnalysis patternAnalysis = BeatmapPatternAnalyzer.analyze(osuBeatmap, null);
        int patternWeight = 0;
        for (BeatmapPatternAnalysis.PatternScore type : patternAnalysis.types()) {
            patternWeight += (int) (PATTERN_WEIGHTS.getOrDefault(type.type(), 10) * type.percentage());
        }
        return patternWeight * (100 - entry.bestIndex()) / 100;
    }

    private record ScoreEntry(int bestIndex, Score score) {
    }

    private record TargetScore(ScoreEntry entry, UserExtended user) {
    }
}
