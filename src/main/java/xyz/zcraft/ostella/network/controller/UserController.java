package xyz.zcraft.ostella.network.controller;

import com.google.gson.*;
import com.google.gson.annotations.SerializedName;
import io.javalin.http.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.zcraft.ostella.addpp.BpProjection;
import xyz.zcraft.ostella.data.*;
import xyz.zcraft.ostella.db.ScoreStore;
import xyz.zcraft.ostella.db.SetMetaStore;
import xyz.zcraft.ostella.exception.ApiException;
import xyz.zcraft.ostella.network.*;
import xyz.zcraft.ostella.service.AsyncService;
import xyz.zcraft.ostella.service.CacheService;
import xyz.zcraft.ostella.service.RenderService;
import xyz.zcraft.ostella.util.ScoreListRange;
import xyz.zcraft.ostella.util.TokenManager;
import xyz.zcraft.ostella.whatif.RankPpModel;
import xyz.zcraft.ostella.whatif.WhatIfEstimates;
import xyz.zcraft.ostella.whatif.WhatIfService;
import xyz.zcraft.osu.model.*;
import xyz.zcraft.osu.parser.*;
import xyz.zcraft.osu.parser.data.beatmap.OsuBeatmap;
import xyz.zcraft.osu.parser.data.replay.OsuReplay;
import xyz.zcraft.osu.parser.data.replay.ReplayAnalyze;

import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static xyz.zcraft.ostella.service.CacheService.tryCache;
import static xyz.zcraft.ostella.util.RequestUtil.*;

public class UserController {
    private static final Logger LOG = LogManager.getLogger(UserController.class);
    private static final Gson GSON = new Gson();
    private static final int USER_INFO_SCORE_SAMPLE_COUNT = 100;
    private static final int USER_INFO_DISPLAY_SCORE_COUNT = 5;

    public final RenderService renderer;
    public final AsyncService executor;
    public final WhatIfService whatIfService;
    public final TokenManager tokenManager;
    public final Router router;

    public UserController(Router router) {
        this.router = router;
        this.renderer = router.renderer;
        this.executor = router.executor;
        this.whatIfService = router.whatIf;
        this.tokenManager = router.tokenManager;
    }

    private static List<String> filterLabels(List<ScoreFilter> filters) {
        return filters.stream().map(ScoreFilter::getDisplayText).toList();
    }

    /**
     * These callers fetched /users/{id}/osu; playmode is a preference, not the response ruleset.
     */
    static void putRankPpHeaders(Context context, UserExtended user) {
        context.header("X-User-Id", String.valueOf(user.getId()));
        var stats = user.getStatistics();
        if (stats == null || stats.getGlobalRank() == null || stats.getGlobalRank() <= 0
                || stats.getPp() == null || !Double.isFinite(stats.getPp()) || stats.getPp() <= 0) return;
        context.header("X-Osu-Ruleset", "osu");
        context.header("X-Osu-Global-Rank", String.valueOf(stats.getGlobalRank()));
        context.header("X-Osu-Total-Pp", String.valueOf(stats.getPp()));
        context.header("X-Osu-Observed-At", String.valueOf(System.currentTimeMillis()));
    }

    private RecentBestData filterBestScoresSince(List<Score> bestScores, Instant cutoff) {
        double currentPp = 0;
        List<ScoreEntry> entries = new ArrayList<>();
        Set<Long> newIds = new HashSet<>();
        for (int index = 0; index < bestScores.size(); index++) {
            Score bestScore = bestScores.get(index);
            currentPp += bestScore.getWeight().getPp();
            try {
                if (bestScore.getEndedAt() != null && !Instant.parse(bestScore.getEndedAt()).isBefore(cutoff)) {
                    entries.add(new ScoreEntry(index + 1, bestScore));
                    newIds.add(bestScore.getId());
                }
            } catch (DateTimeParseException e) {
                LOG.warn("Ignoring best score {} with invalid ended_at: {}", bestScore.getId(), bestScore.getEndedAt());
            }
        }

        double beforePp = 0;
        int weightIndex = 1;
        for (int index = 0; index < bestScores.size() && weightIndex < 100; index++) {
            Score score = bestScores.get(index);
            if (!newIds.contains(score.getId())) {
                beforePp += score.getPp() * Math.pow(0.95, weightIndex - 1);
                weightIndex++;
            }
        }

        return new RecentBestData(new FilteredScores(entries), currentPp - beforePp);
    }

    private FilteredScores applyFilters(List<Score> scores, List<ScoreFilter> filters, int firstPosition) {
        List<ScoreEntry> entries = new ArrayList<>();
        for (int index = 0; index < scores.size(); index++) {
            Score score = scores.get(index);
            if (filters.stream().allMatch(filter -> filter.matches(score, () -> {
                final Long id = score.getBeatmapset().getId();
                final var beatmapset = executor.enqueueAsync(() -> OsuAPI.getBeatmapset(tokenManager.getTokenData(), id)).join();
                tryCache(beatmapset);
                return beatmapset;
            }))) {
                entries.add(new ScoreEntry(index + firstPosition, score));
            }
        }
        if (!filters.isEmpty() && entries.isEmpty()) {
            throw new ApiException(ErrorCode.NO_SCORE_FOUND, "No scores matched the filters");
        }
        return new FilteredScores(entries);
    }

    public void getRecentScores(@NotNull Context context) {
        final long u = requirePathLong(context, "userId");
        final var range = ScoreListRange.from(context);
        final boolean fail = requireBoolean(context, "fail", false);
        final List<ScoreFilter> filters = requireScoreFilters(context, router::getReplayBlocking);

        final ScoreType type = fail ? ScoreType.RECENT : ScoreType.RECENT_PASS;
        context.future(() -> executor.enqueueAsync(() -> OsuAPI.getUserScores(
                        tokenManager.getTokenData(), u, type, range.count(), range.offset())
                )
                .thenCompose(scores -> executor.enqueueAsync(() -> OsuAPI.getUser(tokenManager.getTokenData(), u))
                        .thenApply(user -> {
                            if (user == null) {
                                throw new ApiException(ErrorCode.NO_USER_FOUND, "No user found");
                            }

                            for (Score score : scores) {
                                Router.ensurePp(score);
                            }

                            FilteredScores filteredScores = applyFilters(scores, filters, range.start());
                            return prepareScoreList(context, user, filteredScores, filterLabels(filters), "Recent Scores", null);
                        }))
                .thenCompose(data -> ImageResponse.respond(context, data, this::renderScoreList, renderer.getRenderExecutor())));
    }

    public void getUsers(@NotNull Context context) {
        final JsonElement body = JsonParser.parseString(context.body());
        final var uidArr = body.getAsJsonObject().getAsJsonArray("ids");

        if (uidArr == null || uidArr.isEmpty()) {
            context.status(400).result(Response.error("Missing 'uid' array in request body", ErrorCode.ILLEGAL_ARGUMENT).toString());
            return;
        }

        context.future(() -> {
            List<CompletableFuture<List<User>>> userFutures = new ArrayList<>(uidArr.size() / 50 + 1);
            for (int start = 0; start < uidArr.size(); start += 50) {
                final int end = Math.min(start + 50, uidArr.size());
                List<Long> batch = new ArrayList<>();
                for (int j = start; j < end; j++) {
                    batch.add(uidArr.get(j).getAsLong());
                }
                userFutures.add(executor.enqueueAsync(() -> OsuAPI.getUsers(tokenManager.getTokenData(), batch))
                        .thenApply(users -> {
                            if (users == null || users.isEmpty()) {
                                throw new ApiException(ErrorCode.NO_USER_FOUND, "No users found for the provided ids!");
                            }
                            return users;
                        }));
            }

            return CompletableFuture.allOf(userFutures.toArray(new CompletableFuture[0]))
                    .thenApply(_ -> {
                        JsonArray usersArr = new JsonArray();
                        for (var future : userFutures) {
                            try {
                                final List<User> join = future.join();
                                for (User user : join) {
                                    usersArr.add(GSON.toJsonTree(user));
                                }
                            } catch (CompletionException e) {
                                if (e.getCause() instanceof ApiException apiEx && apiEx.getErrorCode() == ErrorCode.NO_USER_FOUND) {
                                    LOG.warn("User not found for one of the requested ids: {}", apiEx.getMessage());
                                } else {
                                    LOG.error("Error fetching user data", e);
                                }
                            }
                        }
                        return usersArr;
                    }).thenAccept(usersArr -> context.status(200).result(new Response(true, "Success", usersArr).toString()));
        });
    }

    public void getSelf(@NotNull Context context) {
        final String auth = context.header(Headers.OSU_AUTHORIZATION);

        if (auth == null) {
            context.status(401)
                    .result(Response.error("Missing X-Osu-Authorization header", ErrorCode.UNAUTHORIZED).toString());
            return;
        }

        context.future(() -> executor
                .enqueueAsync(() -> OsuAPI.getSelf(auth))
                .thenApply(u -> {
                    if (u == null)
                        throw new ApiException(ErrorCode.NO_USER_FOUND, "No user found for the provided token!");
                    return u;
                })
                .thenCompose(u -> executor.enqueueAsync(() -> OsuAPI.getUser(tokenManager.getTokenData(), u.getId())))
                .thenAccept(u -> context.status(200).result(new Response(true, "Success", GSON.toJsonTree(u)).toString()))
        );
    }

    public void getBestOfN(@NotNull Context context) {
        final long u = requirePathLong(context, "userId");
        final var range = ScoreListRange.from(context);
        final List<ScoreFilter> filters = requireScoreFilters(context, router::getReplayBlocking);
        final boolean hasUrFilter = filters.stream().anyMatch(f -> f.getField() == ScoreFilter.Field.UR);
        final boolean hasTypeFilter = filters.stream().anyMatch(f -> f.getField() == ScoreFilter.Field.TYPE);
        final AtomicInteger filteredForNoReplay = new AtomicInteger(0);
        final AtomicInteger filteredForError = new AtomicInteger(0);
        context.future(() -> executor.enqueueAsync(() -> OsuAPI.getUserScores(
                        tokenManager.getTokenData(), u, ScoreType.BEST, range.count(), range.offset()
                ))
                .thenCompose(scores -> {
                    if (scores == null || scores.isEmpty()) throw new ApiException(ErrorCode.NO_SCORE_FOUND);
                    return executor.enqueueAsync(() -> OsuAPI.getUser(tokenManager.getTokenData(), u))
                            .thenApply(user -> {
                                if (user == null) throw new ApiException(ErrorCode.NO_USER_FOUND);
                                for (Score score : scores) {
                                    Router.ensurePp(score);
                                }
                                FilteredScores filteredScores = applyFilters(scores, filters, range.start());

                                filteredScores.entries()
                                        .forEach(e -> {
                                            try {
                                                final Score score = e.getScore();
                                                final OsuBeatmap osuBeatmap = BeatmapParser.parseBeatmap(CacheService.getBeatmapPath(score.getBeatmapId()));
                                                final OsuReplay osuReplay;
                                                final ReplayAnalyze analyze;
                                                final Optional<Path> replayCache = CacheService.getReplayCache(score.getId());

                                                if (replayCache.isPresent()) {
                                                    osuReplay = ReplayParser.parseReplay(replayCache.get());
                                                    analyze = ReplayAnalyzer.analyze(osuBeatmap, osuReplay);
                                                } else {
                                                    analyze = null;
                                                }

                                                if (hasUrFilter) {
                                                    if (analyze != null) {
                                                        e.addExtra("UR: %.2f".formatted(analyze.unstableRate()));
                                                    } else {
                                                        filteredForNoReplay.incrementAndGet();
                                                    }
                                                }

                                                if (hasTypeFilter) {
                                                    var diffSpec = OsuParser.getDiffSpecForMods(osuBeatmap, score.getMods());
                                                    var pattern = BeatmapPatternAnalyzer.analyze(osuBeatmap, diffSpec.getDifficulty());

                                                    var primary = pattern.primaryType();
                                                    e.addExtra("%s: %d%%".formatted(primary.type().name(), (int) primary.percentage()));
                                                }
                                            } catch (Exception ex) {
                                                LOG.error("Failed to get extra info for score entry", ex);
                                                filteredForError.incrementAndGet();
                                            }
                                        });

                                StringBuilder extraTitle = new StringBuilder();

                                if (filteredForError.get() > 0) {
                                    extraTitle.append(filteredForError.get()).append(" filtered due to error.");
                                }

                                if (filteredForNoReplay.get() > 0) {
                                    extraTitle.append(filteredForNoReplay.get()).append(" filtered for no replay.");
                                }

                                return prepareScoreList(context, user, filteredScores, filterLabels(filters), "Best Scores", extraTitle.toString());
                            });
                })
                .thenCompose(data -> ImageResponse.respond(context, data, this::renderScoreList, renderer.getRenderExecutor())));
    }

    public void getRecentScoresBatch(@NotNull Context context) {
        final RecentScoresBatchBody body = GSON.fromJson(context.body(), RecentScoresBatchBody.class);

        if (body.userIds() == null || body.userIds().isEmpty()) {
            context.status(400).result(Response.error("Missing 'user_ids' array in request body", ErrorCode.ILLEGAL_ARGUMENT).toString());
            return;
        }

        final HashMap<Long, CompletableFuture<List<Score>>> scoreFutures = new HashMap<>();
        for (Long userId : body.userIds()) {
            scoreFutures.put(userId, executor.enqueueAsync(() -> OsuAPI.getUserScores(
                    tokenManager.getTokenData(), userId, body.includeFails() ? ScoreType.RECENT : ScoreType.RECENT_PASS, body.limit())
            ));
        }

        context.future(() -> CompletableFuture.allOf(scoreFutures.values().toArray(new CompletableFuture[0]))
                .thenApply(_ -> {
                    JsonObject result = new JsonObject();
                    for (var entry : scoreFutures.entrySet()) {
                        try {
                            List<Score> scores = entry.getValue().join();
                            JsonArray scoresArr = new JsonArray();
                            for (Score score : scores) {
                                scoresArr.add(GSON.toJsonTree(BatchScore.fromScore(score)));
                            }
                            result.add(String.valueOf(entry.getKey()), scoresArr);
                        } catch (CompletionException e) {
                            if (e.getCause() instanceof ApiException apiEx && apiEx.getErrorCode() == ErrorCode.NO_SCORE_FOUND) {
                                LOG.warn("No recent scores found for user id {}: {}", entry.getKey(), apiEx.getMessage());
                                result.add(String.valueOf(entry.getKey()), new JsonArray());
                            } else {
                                LOG.error("Error fetching recent scores for user id {}", entry.getKey(), e);
                                result.add(String.valueOf(entry.getKey()), new JsonArray());
                            }
                        }
                    }
                    return result;
                })
                .thenAccept(result -> context.status(200).result(new Response(true, "Success", result).toString()))
        );
    }

    public void getTodayBestScores(@NotNull Context context) {
        final long userId = requirePathLong(context, "userId");
        final String daysParam = context.queryParam("days");
        final int days = daysParam == null ? 1 : requirePositiveInt(context, "days");
        final Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        final CompletableFuture<List<Score>> bestScoresFuture = executor.enqueueAsync(() ->
                OsuAPI.getUserScores(
                        tokenManager.getTokenData(), userId, ScoreType.BEST, OsuAPI.MAX_USER_SCORES_LIMIT
                ));

        context.future(() -> bestScoresFuture
                .thenApply(bestScores -> filterBestScoresSince(bestScores, cutoff))
                .thenCompose(data -> {
                    if (data.scores().entries().isEmpty()) {
                        throw new ApiException(ErrorCode.NO_SCORE_FOUND, "No best scores found in the last " + days + " days");
                    }
                    return executor.enqueueAsync(() -> OsuAPI.getUser(tokenManager.getTokenData(), userId))
                            .thenApply(user -> {
                                if (user == null) throw new ApiException(ErrorCode.NO_USER_FOUND);

                                for (ScoreEntry entry : data.scores().entries()) {
                                    Router.ensurePp(entry.getScore());
                                }

                                String extraTitle = "Date range: " + days + (days == 1 ? " Day" : " Days");

                                if (data.ppChange() != null) {
                                    extraTitle += " | Estimated PP Change: %+.2fpp".formatted(data.ppChange());
                                }

                                return prepareScoreList(context, user, data.scores(), List.of(), "Recent Bests", extraTitle);
                            });
                })
                .thenCompose(data -> ImageResponse.respond(context, data, this::renderScoreList, renderer.getRenderExecutor())));
    }

    public void getUserInfo(@NotNull Context context) {
        final long userId = requirePathLong(context, "userId");

        if (ImageResponse.wantsJson(context)) {
            getUserInfoJson(context, userId);
            return;
        }

        final CompletableFuture<UserExtended> userFuture = executor.enqueueAsync(() ->
                OsuAPI.getUser(tokenManager.getTokenData(), userId));
        final CompletableFuture<List<Score>> topScoresFuture = executor.enqueueAsync(() ->
                OsuAPI.getUserScores(
                        tokenManager.getTokenData(),
                        userId,
                        ScoreType.BEST,
                        USER_INFO_SCORE_SAMPLE_COUNT
                ));

        context.future(() -> userFuture.thenCombine(topScoresFuture, UserInfoData::new)
                .thenApplyAsync(data -> {
                    if (data.user() == null) {
                        throw new ApiException(ErrorCode.NO_USER_FOUND, "No user found");
                    }
                    List<Score> displayScores = data.topScores().stream()
                            .limit(USER_INFO_DISPLAY_SCORE_COUNT)
                            .toList();
                    for (Score score : displayScores) {
                        Router.ensurePp(score);
                    }

                    putRankPpHeaders(context, data.user());
                    context.header(
                            "X-Score-Ids",
                            displayScores.stream()
                                    .map(Score::getId)
                                    .map(String::valueOf)
                                    .collect(Collectors.joining(","))
                    );
                    return renderer.renderUserInfo(data.user(), data.topScores());
                }, renderer.getRenderExecutor())
                .thenAccept(bytes -> context.status(200).contentType("image/png").result(bytes)));
    }

    public void getUserRank(@NotNull Context context) {
        final long userId = requirePathLong(context, "userId");
        final CompletableFuture<UserExtended> userFuture = executor.enqueueAsync(() ->
                OsuAPI.getUser(tokenManager.getTokenData(), userId));

        context.future(() -> userFuture.thenApply(userExtended -> {
                    JsonObject result = new JsonObject();
                    result.addProperty("id", userExtended.getId());
                    result.addProperty("username", userExtended.getUsername());
                    result.addProperty("global_rank", userExtended.getStatistics().getGlobalRank());
                    result.addProperty("country_rank", userExtended.getStatistics().getRank().getCountry());
                    result.addProperty("country_code", userExtended.getCountryCode());
                    return result;
                })
                .thenAccept(json -> putResult(context, json)));
    }

    private ScoreListData prepareScoreList(
            Context context, UserExtended user, FilteredScores scores, List<String> filters, String title, String extraTitle
    ) {
        putRankPpHeaders(context, user);
        context.header("X-Score-Ids", scores.entries().stream().map(ScoreEntry::getScore).map(Score::getId)
                .map(String::valueOf).collect(Collectors.joining(",")));
        return new ScoreListData(user, scores.entries(), filters, title, extraTitle);
    }

    public void getFriends(@NotNull Context context) {
        final String auth = context.header(Headers.OSU_AUTHORIZATION);

        if (auth == null) {
            context.status(401)
                    .result(Response.error("Missing X-Osu-Authorization header", ErrorCode.UNAUTHORIZED).toString());
            return;
        }

        context.future(() -> executor
                .enqueueAsync(() -> OsuAPI.getFriends(auth))
                .thenApply(r -> {
                    if (r == null)
                        throw new ApiException(ErrorCode.NO_USER_FOUND, "No user found for the provided token!");
                    JsonArray arr = new JsonArray();
                    r.forEach(ur -> {
                        JsonObject obj = new JsonObject();
                        obj.add("user", GSON.toJsonTree(ur.target()));
                        obj.addProperty("mutual", ur.mutual());
                        arr.add(obj);
                    });
                    return arr;
                })
                .thenAccept(arr -> context.status(200).result(new Response(true, "Success", arr).toString()))
        );
    }

    public void lookupUser(@NotNull Context context) {
        final UserLookupBody body = GSON.fromJson(context.body(), UserLookupBody.class);

        if (body.userName() == null || body.userName().isBlank()) {
            context.status(400).result(Response.error("Missing 'user_name' in request body", ErrorCode.ILLEGAL_ARGUMENT).toString());
            return;
        }

        context.future(() -> executor
                .enqueueAsync(() -> OsuAPI.getUser(tokenManager.getTokenData(), body.userName()))
                .thenApply(u -> {
                    if (u == null)
                        throw new ApiException(ErrorCode.NO_USER_FOUND, "No user found for the provided username!");
                    return (User) u;
                })
                .thenAccept(u -> context.status(200).result(new Response(true, "Success", GSON.toJsonTree(u)).toString()))
        );
    }

    private void getUserInfoJson(@NotNull Context context, long userId) {
        context.future(() -> executor.enqueueAsync(() -> OsuAPI.getUser(tokenManager.getTokenData(), userId))
                .thenAccept(user -> {
                    if (user == null) throw new ApiException(ErrorCode.NO_USER_FOUND);
                    putRankPpHeaders(context, user);
                    putResult(context, user);
                }));
    }

    private byte[] renderScoreList(ScoreListData data) {
        SetMetaStore.upsertBeatmapset(
                data.scores().stream()
                        .map(ScoreEntry::getScore)
                        .map(Score::getBeatmapset)
                        .filter(Objects::nonNull)
                        .toList()
        );
        return renderer.renderScores(data.user(), data.scores(), data.filters(), data.title(), data.extraTitle());
    }

    private byte[] renderDerankedBest(DerankedBestData data) {
        return renderer.renderDerankedBest(
                data.user(), data.scores(), data.newGlobalRank(), data.newPp(),
                data.counts(), data.ppCounts()
        );
    }

    public void getDerankedBest(@NotNull Context ctx) {
        final long u = requirePathLong(ctx, "userId");
        final var range = ScoreListRange.from(ctx);
        final int endPosition = range.end();
        final int start = range.start();

        ctx.future(() -> executor
                .enqueueAsync(() -> OsuAPI.getUserScores(tokenManager.getTokenData(), u, ScoreType.BEST, 200))
                .thenCompose(scores -> {
                    ScoreStore.upsertScores(scores);
                    var merged = ScoreStore.getUserBests(u, 200).stream()
                            .map(ScoreRecord::toScore)
                            .sorted(Comparator.comparing(Score::getPp).reversed())
                            .toList();
                    if (start > merged.size()) throw new ApiException(ErrorCode.NO_SCORE_FOUND);
                    return fillDerankedMetadata(merged, scores, start, endPosition)
                            .thenApply(_ -> new DerankedScores(scores.stream().map(Score::getPp).toList(), merged));
                })
                .thenCompose(data -> {
                    var scores = data.merged().stream()
                            .sorted(Comparator.comparing(Score::getPp).reversed()).toList();
                    List<ScoreEntry> entries = new ArrayList<>(scores.size());

                    int rankedCount = 0;
                    int unrankedCount = 0;
                    int localCount = 0;

                    double rankedPp = 0;
                    double unrankedPp = 0;
                    double localPp = 0;

                    for (int i = 0; i < scores.size(); i++) {
                        final Score current = scores.get(i);
                        final ScoreEntry entry = new ScoreEntry(i + 1, current);
                        entries.add(entry);
                        final Score.Weight weight = new Score.Weight();
                        final double percentage = Math.pow(0.95, i);
                        final double scorePp = current.getPp() * percentage;
                        weight.setPercentage(percentage * 100.0);
                        weight.setPp(scorePp);
                        current.setWeight(weight);

                        if (current.getRanked() != null
                                && current.getRanked()
                                && "RANKED".equalsIgnoreCase(current.getBeatmap().getStatus())
                                && isModsRankable(current.getMods())
                        ) {
                            entry.setScoreType("Ranked");
                            rankedCount++;
                            rankedPp += scorePp;
                        } else if (current.getId() < 0) {
                            entry.setScoreType("Local");
                            localCount++;
                            localPp += scorePp;
                        } else {
                            entry.setScoreType("Unranked");
                            unrankedCount++;
                            unrankedPp += scorePp;
                        }
                    }

                    final var scoreCounts = new ScoreCategoryTotals<>(rankedCount, unrankedCount, localCount);
                    final var ppCounts = new ScoreCategoryTotals<>(rankedPp, unrankedPp, localPp);

                    if (start > scores.size()) throw new ApiException(ErrorCode.NO_SCORE_FOUND);
                    final int end = Math.min(scores.size(), endPosition);
                    final List<ScoreEntry> rangedScores = entries.subList(start - 1, end);
                    ctx.header("X-Score-Ids", rangedScores.stream().map(ScoreEntry::getScore).map(Score::getId)
                            .map(String::valueOf).collect(Collectors.joining(",")));

                    return executor.enqueueAsync(() -> OsuAPI.getUser(tokenManager.getTokenData(), u))
                            .thenApply(user -> {
                                if (user == null) throw new ApiException(ErrorCode.NO_USER_FOUND);
                                final double currentPp = user.getStatistics().getPp();
                                final double newPp = BpProjection.projectBests(currentPp, data.officialPp(),
                                        scores.stream().map(Score::getPp).toList(), 200);
                                Long newRank = user.getStatistics().getGlobalRank();
                                if (newPp > currentPp) {
                                    var samples = new HashSet<>(whatIfService.getModelAround(newPp, null).samples());
                                    if (currentPp > 0)
                                        samples.addAll(whatIfService.getModelAround(currentPp, null).samples());
                                    newRank = WhatIfEstimates.project(RankPpModel.from(samples),
                                            currentPp, newPp, newRank).rank();
                                }
                                putRankPpHeaders(ctx, user);
                                return new DerankedBestData(
                                        user, rangedScores, newRank, newPp, scoreCounts, ppCounts
                                );
                            });
                })
                .thenCompose(data -> ImageResponse.respond(ctx, data, this::renderDerankedBest, renderer.getRenderExecutor())));
    }

    private static final Set<String> RANKED_MODS = Set.of(
            "EZ", "NF", "HT", "DC",
            "HR", "SD", "PF", "DT", "NC", "HD", "TC", "FL", "BL", "AC",
            "SO", "AL", "SG", "MU", "NS", "TD"
    );

    private static boolean isModsRankable(List<Mod> mods) {
        if (mods == null || mods.isEmpty()) return true;

        return mods.stream().allMatch(m -> {
            String acronym = m.getAcronym();
            if (acronym == null) return false;

            acronym = acronym.toUpperCase(Locale.ROOT);
            if (!RANKED_MODS.contains(acronym)) return false;

            var settings = m.getSettings();

            return switch (acronym) {
                case "HT" -> hasDefaultSpeed(settings, 0.75, true);
                case "DC" -> hasDefaultSpeed(settings, 0.75, false);
                case "DT" -> hasDefaultSpeed(settings, 1.5, true);
                case "NC" -> hasDefaultSpeed(settings, 1.5, false);

                case "SD", "PF", "AC", "MU", "NS" -> true;

                default -> settings == null || settings.isEmpty();
            };
        });
    }

    private static boolean hasDefaultSpeed(
            Map<String, ?> settings,
            double defaultSpeed,
            boolean allowPitch
    ) {
        if (settings == null || settings.isEmpty()) return true;

        for (String key : settings.keySet()) {
            if (!key.equals("speed_change") && !(allowPitch && key.equals("adjust_pitch"))) {
                return false;
            }
        }

        Object speed = settings.get("speed_change");
        if (speed == null) return true;

        try {
            return Double.compare(Double.parseDouble(speed.toString()), defaultSpeed) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private CompletableFuture<Void> fillDerankedMetadata(
            List<Score> scores, List<Score> official, int start, int end
    ) {
        Map<Long, CompletableFuture<BeatmapExtended>> beatmaps = new HashMap<>();
        Map<Long, CompletableFuture<Beatmapset>> sets = new ConcurrentHashMap<>();
        for (Score score : official) {
            if (score.getBeatmap() != null) {
                beatmaps.put(score.getBeatmap().getId(), CompletableFuture.completedFuture(score.getBeatmap()));
            }
            if (score.getBeatmapset() != null) {
                sets.put(score.getBeatmapset().getId(), CompletableFuture.completedFuture(score.getBeatmapset()));
            }
        }

        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (int i = 0; i < scores.size(); i++) {
            Score score = scores.get(i);
            boolean displayed = i >= start - 1 && i < end;
            // Off-page scores only need map status for the ranked/unranked totals.
            if (!displayed && !Boolean.TRUE.equals(score.getRanked())) continue;
            var beatmap = beatmaps.computeIfAbsent(score.getBeatmapId(), id ->
                    CacheService.getBeatmapJsonCache(id).map(CompletableFuture::completedFuture)
                            .orElseGet(() -> executor.enqueueAsync(() -> {
                                var fetched = OsuAPI.getBeatmap(tokenManager.getTokenData(), id);
                                if (fetched == null) throw new ApiException(ErrorCode.NO_BEATMAP_FOUND);
                                tryCache(fetched);
                                return fetched;
                            })));
            futures.add(beatmap.thenCompose(map -> {
                score.setBeatmap(map);
                if (!displayed) return CompletableFuture.completedFuture(null);
                return sets.computeIfAbsent(map.getBeatmapsetId(), id ->
                        CacheService.getBeatmapsetJsonCache(id).map(CompletableFuture::completedFuture)
                                .orElseGet(() -> executor.enqueueAsync(() -> {
                                    var fetched = OsuAPI.getBeatmapset(tokenManager.getTokenData(), id);
                                    if (fetched == null) throw new ApiException(ErrorCode.NO_BEATMAPSET_FOUND);
                                    tryCache(fetched);
                                    return fetched;
                                }))).thenAccept(score::setBeatmapset);
            }));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private record RecentBestData(FilteredScores scores, Double ppChange) {
    }

    private record DerankedScores(List<Double> officialPp, List<Score> merged) {
    }

    private record ScoreListData(UserExtended user, List<ScoreEntry> scores,
                                 List<String> filters, String title, String extraTitle) {
    }

    private record DerankedBestData(
            UserExtended user, List<ScoreEntry> scores, Long newGlobalRank, double newPp,
            ScoreCategoryTotals<Integer> counts, ScoreCategoryTotals<Double> ppCounts
    ) {
    }

    record FilteredScores(List<ScoreEntry> entries) {
    }

    private record UserInfoData(UserExtended user, List<Score> topScores) {
    }

    public record UserLookupBody(
            @SerializedName("user_name") String userName
    ) {
    }

    public record RecentScoresBatchBody(
            @SerializedName("user_ids") List<Long> userIds,
            @SerializedName("limit") int limit,
            @SerializedName("include_fails") boolean includeFails
    ) {
    }

    public record BatchScore(
            @SerializedName("beatmap_id") long beatmapId,
            @SerializedName("beatmapset_id") long beatmapsetId,
            @SerializedName("score_id") long scoreId,
            @SerializedName("user_id") long userId,
            @SerializedName("full_name") String fullName,
            @SerializedName("total_score") long totalScore,
            @SerializedName("rank") String rank,
            @SerializedName("accuracy") double accuracy,
            @SerializedName("max_combo") long maxCombo,
            @SerializedName("pp") double pp,
            @SerializedName("mods") String mods
    ) {
        public static BatchScore fromScore(Score score) {
            return new BatchScore(
                    score.getBeatmapId(),
                    score.getBeatmapset().getId(),
                    score.getId(),
                    score.getUserId(),
                    "%s - %s [%.2f★ %s]".formatted(
                            score.getBeatmapset().getArtist(),
                            score.getBeatmapset().getTitle(),
                            score.getBeatmap().getDifficultyRating(),
                            score.getBeatmap().getVersion()),
                    score.getTotalScore(),
                    score.getRank(),
                    score.getAccuracy(),
                    score.getMaxCombo(),
                    score.getPp() != null ? score.getPp() : 0.0,
                    ModSettings.format(score.getMods())
            );
        }
    }
}
