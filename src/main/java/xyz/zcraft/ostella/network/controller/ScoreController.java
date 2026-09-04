package xyz.zcraft.ostella.network.controller;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
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
import xyz.zcraft.ostella.util.RequestUtil;
import xyz.zcraft.ostella.util.TokenManager;
import xyz.zcraft.ostella.util.WeightedRandom;
import xyz.zcraft.ostella.util.format.ScoreFormatUtil;
import xyz.zcraft.osu.model.BeatmapExtended;
import xyz.zcraft.osu.model.Mod;
import xyz.zcraft.osu.model.Score;
import xyz.zcraft.osu.model.UserExtended;
import xyz.zcraft.osu.parser.BeatmapAnalyzer;
import xyz.zcraft.osu.parser.BeatmapParser;
import xyz.zcraft.osu.parser.BeatmapPatternAnalyzer;
import xyz.zcraft.osu.parser.OsuParser;
import xyz.zcraft.osu.parser.data.beatmap.BeatmapPatternAnalysis;
import xyz.zcraft.osu.parser.data.beatmap.DiffSpec;
import xyz.zcraft.osu.parser.data.beatmap.DifficultyAttribute;
import xyz.zcraft.osu.parser.data.beatmap.OsuBeatmap;
import xyz.zcraft.osu.parser.exception.AnalyzeException;
import xyz.zcraft.osu.parser.exception.ParseException;

import java.util.*;
import java.util.concurrent.CompletableFuture;
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
            BeatmapPatternAnalysis.PatternType.AIM, 10
    );
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

    public static long getModBits(List<Mod> mods) {
        long bits = 0;

        for (Mod mod : mods) {
            switch (mod.getAcronym()) {
                case "EZ" -> bits |= 2L;
                case "HR" -> bits |= 16L;
                case "DT" -> bits |= 64L;
                case "HT" -> bits |= 256L;
                case "NC" -> bits |= 512L | 64L;
            }
        }

        return bits;
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
                            WeightedRandom<Long> userIds = new WeightedRandom<>();

                            scores.stream()
                                    .map(Score::getUserId)
                                    .distinct()
                                    .forEach(l -> userIds.add(l, 1));

                            return userIds;
                        })
                        .thenCompose(userIds -> findAvailableScore(userIds, minRank, Map.of()))
                        .thenApply(ScoreController::mapScoreToJson)
                        .thenAccept(result ->
                                context.status(200).result(new Response(true, "Success", result).toString())
                        )
        );
    }

    public void randomScoreFromUsers(@NotNull Context context) {
        final JsonObject body = JsonParser.parseString(context.body()).getAsJsonObject();

        final RandomScoreRequest randomScoreRequest = GSON.fromJson(body, RandomScoreRequest.class);

        if (randomScoreRequest.uids() == null || randomScoreRequest.uids().isEmpty()) {
            throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, "No users provided!");
        }

        WeightedRandom<Long> userIds = new WeightedRandom<>();

        for (Long uid : randomScoreRequest.uids()) {
            final double weight = Optional.ofNullable(randomScoreRequest.weightFactor())
                    .map(WeightFactor::users)
                    .map(m -> m.get(uid))
                    .orElse(1.0);

            if (weight < 0.0) {
                continue;
            }

            userIds.add(uid, weight);
        }

        final Map<Long, Double> scoreWeights = Optional.ofNullable(randomScoreRequest.weightFactor())
                .map(WeightFactor::scores)
                .orElse(Map.of());

        context.future(() ->
                findAvailableScore(userIds, Long.MAX_VALUE, scoreWeights)
                        .thenApply(ScoreController::mapScoreToJson)
                        .thenAccept(result ->
                                context.status(200).result(new Response(true, "Success", result).toString())
                        )
        );
    }

    public void randomScoreFromUsersWeights(@NotNull Context context) {
        final long userId = requirePathLong(context, "userId");

        final JsonElement jsonElement = JsonParser.parseString(context.body());
        final Map<Long, Double> scoreWeights;

        if (jsonElement == null || !jsonElement.isJsonObject()) {
            scoreWeights = Map.of();
        } else {
            final JsonObject body = jsonElement.getAsJsonObject();

            final RandomScoreRequest randomScoreRequest = GSON.fromJson(body, RandomScoreRequest.class);

            scoreWeights = Optional.ofNullable(randomScoreRequest.weightFactor())
                    .map(WeightFactor::scores)
                    .orElse(Map.of());
        }
        context.future(() ->
                executor.enqueueAsync(() -> OsuAPI.getUserScores(tokenManager.getTokenData(), userId, ScoreType.BEST, 80))
                        .thenApply(scores -> {
                            List<ScoreEntry> candidates = new ArrayList<>(scores.size());

                            for (int i = 0; i < scores.size(); i++) {
                                final Score score = scores.get(i);
                                if (!ScoreFormatUtil.replayPresent(score)) {
                                    continue;
                                }

                                final ScoreEntry scoreEntry = new ScoreEntry(i + 1, score);
                                candidates.add(scoreEntry);
                            }


                            final Map<ScoreEntry, Double> weights = getWeights(candidates, scoreWeights);

                            if (weights.isEmpty()) {
                                return "No scores found!";
                            }

                            StringBuilder sb = new StringBuilder();

                            final List<Map.Entry<ScoreEntry, Double>> list = weights.entrySet()
                                    .stream()
                                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).toList();

                            final int LIMIT = 12;
                            for (int i = 0; i < Math.min(LIMIT, list.size()); i++) {
                                final Map.Entry<ScoreEntry, Double> e = list.get(i);
                                sb.append("__BP").append("%02d".formatted(e.getKey().bestIndex())).append("__:")
                                        .append(String.format("%.5f", e.getValue())).append("   ");
                            }

                            if (list.size() > LIMIT) {
                                sb.append("\n").append("... and %d more".formatted(list.size() - LIMIT));
                            }

                            return sb.toString().trim();
                        })
                        .thenAccept(result -> RequestUtil.putResult(context, result))

        );
    }

    private CompletableFuture<TargetScore> findAvailableScore(WeightedRandom<Long> userIds, long minRank, Map<Long, Double> weights) {
        if (userIds.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new ApiException(ErrorCode.NO_SCORE_FOUND, "No available scores found!")
            );
        }

        long userId = userIds.getAndRemove();

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
                return findAvailableScore(userIds, minRank, weights);
            }

            List<ScoreEntry> candidates = new ArrayList<>(SCORE_LIMIT);

            for (int i = 0; i < SCORE_LIMIT; i++) {
                final Score score = scores.get(i);

                if (!ScoreFormatUtil.replayPresent(score)) {
                    continue;
                }

                candidates.add(new ScoreEntry(i + 1, score));
            }

            if (candidates.isEmpty()) {
                return findAvailableScore(userIds, minRank, weights);
            }

            return executor.enqueueAsync(() -> OsuAPI.getUser(tokenManager.getTokenData(), userId))
                    .thenCompose(user -> {
                        if (user == null
                                || user.getStatistics().getGlobalRank() == null
                                || user.getStatistics().getGlobalRank() > minRank) {
                            return findAvailableScore(userIds, minRank, weights);
                        }

                        WeightedRandom<ScoreEntry> randomScores = new WeightedRandom<>();

                        final Map<ScoreEntry, Double> finalWeights = getWeights(candidates, weights);

                        if (finalWeights.isEmpty()) {
                            return findAvailableScore(userIds, minRank, weights);
                        }

                        for (Map.Entry<ScoreEntry, Double> entry : finalWeights.entrySet()) {
                            randomScores.add(entry.getKey(), entry.getValue());
                        }

                        ScoreEntry selected = randomScores.next();

                        return CompletableFuture.completedFuture(new TargetScore(selected, user));
                    });
        });
    }

    private Map<ScoreEntry, Double> getWeights(List<ScoreEntry> candidates, Map<Long, Double> weights) {
        HashMap<ScoreEntry, Double> baseWeights = new HashMap<>();

        for (ScoreEntry current : candidates) {
            try {
                baseWeights.put(current, getWeight(current));
            } catch (ParseException e) {
                LOG.warn("Failed to parse beatmap with id {}", current.score().getBeatmapId(), e);
            }
        }

        if (baseWeights.isEmpty()) {
            return Map.of();
        }

        final double maxWeight = baseWeights.values()
                .stream()
                .max(Double::compareTo)
                .get();

        HashMap<ScoreEntry, Double> finalWeights = new HashMap<>();

        for (Map.Entry<ScoreEntry, Double> entry : baseWeights.entrySet()) {
            final double normalizedWeight = entry.getValue() / maxWeight;

            if (normalizedWeight < 0.5 && entry.getKey().bestIndex() > 40) {
                continue;
            }

            final double powWeight = Math.pow(normalizedWeight, 4);

            final double extraFactor = weights.getOrDefault(entry.getKey().score().getId(), 1.0);

            final double finalWeight = powWeight * extraFactor;

            if (finalWeight < 0.0) {
                continue;
            }

            finalWeights.put(entry.getKey(), finalWeight);
        }

        // Extra normalization to make the result more human-friendly, maybe...
        final double maxFinalWeight = finalWeights.values()
                .stream()
                .max(Double::compareTo)
                .orElseThrow();

        for (var entry : finalWeights.entrySet()) {
            entry.setValue(entry.getValue() / maxFinalWeight);
        }

        return finalWeights;
    }

    private double getWeight(ScoreEntry entry) throws ParseException {
        final Long beatmapId = entry.score().getBeatmapId();
        final OsuBeatmap osuBeatmap = BeatmapParser.parseBeatmap(CacheService.getBeatmapPath(beatmapId));
        final DifficultyAttribute difficultyAttribute = BeatmapAnalyzer.calculateDifficulty(osuBeatmap, getModBits(entry.score().getMods()));
        final BeatmapPatternAnalysis patternAnalysis = BeatmapPatternAnalyzer.analyze(osuBeatmap, difficultyAttribute);
        double patternWeight = 0;
        for (BeatmapPatternAnalysis.PatternScore type : patternAnalysis.types()) {
            patternWeight += (PATTERN_WEIGHTS.getOrDefault(type.type(), 10) * type.percentage());
        }

        final double modWeightFactor = getModWeightFactor(entry);
        final double attributeFactor = getAttributeFactor(difficultyAttribute);

        return (patternWeight * (100.0 - entry.bestIndex()) * modWeightFactor * attributeFactor) / 100.0;
    }

    private double getModWeightFactor(ScoreEntry entry) {
        final ModSet mods = new ModSet(entry.score().getMods().stream().map(Mod::getAcronym).filter(Objects::nonNull).collect(Collectors.toSet()));

        if (mods.is("EZHD"))
            return 2.0;

        if (mods.is("EZ"))
            return 1.5;

        if (mods.is("HRHD"))
            return 1.5;

        if (mods.is("HR"))
            return 1.25;

        if (mods.is("HDDT") || mods.is("HDNC"))
            return 0.8;

        return 1.0;
    }

    private double getAttributeFactor(DifficultyAttribute difficultyAttribute) {
        double attributeFactor = 1.0;

        // Precision...
        if (difficultyAttribute.cs() >= 8) {
            attributeFactor *= 1.1;
        }

        // Reading!
        attributeFactor *= getArFactor(difficultyAttribute.ar());

        return attributeFactor;
    }

    private static double getArFactor(double ar) {
        if (ar >= 8.0) {
            return 1.0;
        }

        return 1.0 + 2.5 * Math.pow((8.0 - ar) / 4.0, 1.25);
    }

    private record ModSet(Set<String> acronyms) {
        public boolean is(Collection<String> acronyms) {
            return this.acronyms.containsAll(acronyms) && this.acronyms.size() == acronyms.size();
        }

        public boolean is(String acronyms) {
            if (acronyms.length() % 2 != 0) {
                throw new IllegalArgumentException("Invalid mod string: " + acronyms);
            }

            List<String> result = new ArrayList<>();

            for (int i = 0; i < acronyms.length(); i += 2) {
                result.add(acronyms.substring(i, i + 2));
            }

            return this.is(result);
        }
    }

    private record ScoreEntry(int bestIndex, Score score) {
    }

    private record TargetScore(ScoreEntry entry, UserExtended user) {
    }

    private record RandomScoreRequest(
            List<Long> uids,
            @SerializedName("weight_factor") WeightFactor weightFactor
    ) {
    }

    private record WeightFactor(
            Map<Long, Double> scores,
            Map<Long, Double> users
    ) {
    }
}
