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
import xyz.zcraft.ostella.service.PpCalculatorService;
import xyz.zcraft.ostella.service.RenderService;
import xyz.zcraft.ostella.util.ScoreListRange;
import xyz.zcraft.ostella.util.Scores;
import xyz.zcraft.ostella.util.TokenManager;
import xyz.zcraft.ostella.whatif.RankPpModel;
import xyz.zcraft.ostella.whatif.WhatIfEstimates;
import xyz.zcraft.ostella.whatif.WhatIfService;
import xyz.zcraft.osu.model.*;
import xyz.zcraft.osu.parser.BeatmapParser;
import xyz.zcraft.osu.parser.OsuParser;
import xyz.zcraft.osu.parser.data.beatmap.DiffSpec;
import xyz.zcraft.osu.parser.data.beatmap.OsuBeatmap;
import xyz.zcraft.osu.parser.exception.AnalyzeException;
import xyz.zcraft.osu.parser.exception.ParseException;

import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
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

    static RecentBestData filterBestScoresSince(List<Score> bestScores, Instant cutoff) {
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

        for (Score score : bestScores) {
            if (!newIds.contains(score.getId())) {
                beforePp += score.getPp() * Math.pow(0.95, weightIndex - 1);
                weightIndex++;
            }
        }

        return new RecentBestData(entries, currentPp - beforePp);
    }

    static List<Score> mergeBeatmapScores(List<Score> official, List<Score> stored) {
        var byMods = new LinkedHashMap<Set<JsonObject>, Score>();
        for (Score score : official) {
            byMods.putIfAbsent(beatmapScoreMods(score), score);
        }
        for (Score score : stored) {
            if (Scores.getRankStatus(score) != Scores.RankStatus.RANKED) {
                byMods.putIfAbsent(beatmapScoreMods(score), score);
            }
        }
        return List.copyOf(byMods.values());
    }

    private static Set<JsonObject> beatmapScoreMods(Score score) {
        if (score.getMods() == null) return Set.of();
        return score.getMods().stream().map(mod -> {
            var key = GSON.toJsonTree(mod).getAsJsonObject();
            var settings = key.getAsJsonObject("settings");
            if (settings != null && settings.isEmpty()) key.remove("settings");
            return key;
        }).collect(Collectors.toSet());
    }

    private static List<ScoreEntry> numberedScores(List<Score> scores, int firstPosition) {
        List<ScoreEntry> entries = new ArrayList<>(scores.size());
        for (Score score : scores) {
            entries.add(new ScoreEntry(firstPosition + entries.size(), score));
        }
        return entries;
    }

    private static void putScoreListHeaders(Context context, UserExtended user, List<ScoreEntry> scores) {
        putRankPpHeaders(context, user);
        context.header("X-Score-Ids", scores.stream().map(ScoreEntry::getScore).map(Score::getId)
                .map(String::valueOf).collect(Collectors.joining(",")));
    }

    private FilteredScores applyFilters(List<ScoreEntry> candidates, List<ScoreFilter> filters, String extraTitle) {
        var counter = new ScoreFilter.FilterStatusCounter();
        List<ScoreEntry> entries = candidates.stream().filter(entry ->
                filters.stream().allMatch(filter -> filter.matches(entry, () -> {
                    long id = entry.getScore().getBeatmapset().getId();
                    var set = executor.enqueueAsync(() -> OsuAPI.getBeatmapset(tokenManager.getTokenData(), id)).join();
                    tryCache(set);
                    return set;
                }, counter))).toList();
        if (!filters.isEmpty() && entries.isEmpty()) {
            throw new ApiException(ErrorCode.NO_SCORE_FOUND, "No scores matched the filters");
        }
        var notes = new StringJoiner(" ");
        if (counter.getFilteredForError().get() > 0)
            notes.add(counter.getFilteredForError().get() + " filtered due to error.");
        if (counter.getFilteredForNoReplay().get() > 0)
            notes.add(counter.getFilteredForNoReplay().get() + " filtered for no replay.");
        if (notes.length() > 0)
            extraTitle = extraTitle == null || extraTitle.isEmpty() ? notes.toString() : extraTitle + " | " + notes;
        return new FilteredScores(entries, filterLabels(filters), extraTitle);
    }

    public void getRecentScores(@NotNull Context context) {
        boolean fail = requireBoolean(context, "fail", false);
        getRangedScores(context, fail ? ScoreType.RECENT : ScoreType.RECENT_PASS, "Recent Scores");
    }

    public void getBestOfN(@NotNull Context context) {
        getRangedScores(context, ScoreType.BEST, "Best Scores");
    }

    private void getRangedScores(Context context, ScoreType type, String title) {
        long userId = requirePathLong(context, "userId");
        var verbosity = ResponseVerbosity.forJson(context);
        var range = ScoreListRange.from(context);
        var filters = requireScoreFilters(context, router::getReplayBlocking);
        context.future(() -> executor.enqueueAsync(() -> OsuAPI.getUserScores(
                        tokenManager.getTokenData(), userId, type, range.count(), range.offset()))
                .thenCompose(scores -> {
                    if (type == ScoreType.BEST && (scores == null || scores.isEmpty()))
                        throw new ApiException(ErrorCode.NO_SCORE_FOUND);
                    return getScoreListUser(userId).thenApply(user -> {
                        scores.forEach(Router::ensurePp);
                        var result = applyFilters(numberedScores(scores, range.start()), filters,
                                type == ScoreType.BEST ? "" : null);
                        return prepareScoreList(context, user, result, title);
                    });
                })
                .thenCompose(data -> ImageResponse.respond(
                        context, data, verbosity::project, this::renderScoreList, renderer.getRenderExecutor())));
    }

    private CompletableFuture<UserExtended> getScoreListUser(long userId) {
        return executor.enqueueAsync(() -> {
            var user = OsuAPI.getUser(tokenManager.getTokenData(), userId);
            if (user == null) throw new ApiException(ErrorCode.NO_USER_FOUND);
            return user;
        });
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
        final var verbosity = ResponseVerbosity.forJson(context);
        final var filters = requireScoreFilters(context, router::getReplayBlocking);
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
                    if (data.scores().isEmpty()) {
                        throw new ApiException(ErrorCode.NO_SCORE_FOUND, "No best scores found in the last " + days + " days");
                    }
                    return getScoreListUser(userId)
                            .thenApply(user -> {
                                for (ScoreEntry entry : data.scores()) {
                                    Router.ensurePp(entry.getScore());
                                }

                                String extraTitle = "Date range: " + days + (days == 1 ? " Day" : " Days");

                                if (data.ppChange() != null) {
                                    extraTitle += " | Estimated PP Change: %+.2fpp".formatted(data.ppChange());
                                }

                                return prepareScoreList(context, user, applyFilters(data.scores(), filters, extraTitle), "Recent Bests");
                            });
                })
                .thenCompose(data -> ImageResponse.respond(
                        context, data, verbosity::project, this::renderScoreList, renderer.getRenderExecutor())));
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

    public void getUserCard(@NotNull Context context) {
        final long userId = requirePathLong(context, "userId");

        context.future(() -> executor.enqueueAsync(() -> OsuAPI.getUser(tokenManager.getTokenData(), userId))
                .thenApplyAsync(renderer::renderUserCard, renderer.getRenderExecutor())
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
            Context context, UserExtended user, FilteredScores scores, String title
    ) {
        putScoreListHeaders(context, user, scores.entries());
        return new ScoreListData(user, scores.entries(), scores.filters(), title, scores.extraTitle());
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

        if ((body.userName() == null || body.userName().isBlank())) {
            context.status(400).result(Response.error("Missing 'user_name' in request body", ErrorCode.ILLEGAL_ARGUMENT).toString());
            return;
        }

        context.future(() -> executor.enqueueAsync(() -> OsuAPI.getUser(tokenManager.getTokenData(), body.userName()))
                .thenAccept(u -> putResult(context, u))
        );
    }

    public void lookupUserBatch(@NotNull Context context) {
        final UserLookupBody body = GSON.fromJson(context.body(), UserLookupBody.class);

        if ((body.userName() == null || body.userName().isBlank())
                && (body.userNames() == null || body.userNames().isEmpty())) {
            context.status(400).result(Response.error("Missing 'user_name' or 'user_names' in request body", ErrorCode.ILLEGAL_ARGUMENT).toString());
            return;
        }

        Set<String> names = new HashSet<>();

        if (body.userNames() != null) {
            names.addAll(body.userNames());
        }

        if (body.userName() != null) {
            names.add(body.userName());
        }


        final var futures = names.stream()
                .map(u -> executor.enqueueAsync(() -> (User) OsuAPI.getUser(tokenManager.getTokenData(), u)))
                .toList();

        context.future(() -> CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(u -> {
                    final List<User> list = futures.stream().map(CompletableFuture::join).toList();
                    if (list.isEmpty())
                        throw new ApiException(ErrorCode.NO_USER_FOUND, "No user found for the provided username!");
                    HashMap<String, User> userMap = new HashMap<>();
                    for (User user : list) {
                        userMap.put(user.getUsername(), user);
                    }
                    return userMap;
                })
                .thenAccept(u -> putResult(context, u))
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
                data.user(), data.scores(), data.newGlobalRank(),
                data.newPp(), data.bonusPp(),
                data.counts(), data.ppCounts(), data.filters(), data.extraTitle()
        );
    }

    private byte[] renderReworkedBest(ReworkedBestData data) {
        return renderer.renderReworkedBest(data);
    }

    public void getDerankedBest(@NotNull Context ctx) {
        final var verbosity = ResponseVerbosity.forJson(ctx);
        final long u = requirePathLong(ctx, "userId");
        final var range = ScoreListRange.from(ctx);
        final var filters = requireScoreFilters(ctx, router::getReplayBlocking);
        final int endPosition = range.end();
        final int start = range.start();

        ctx.future(() -> loadDerankedScores(u, range, filters)
                .thenCompose(data -> {
                    var scores = data.merged();
                    var entries = numberedScores(scores, 1);

                    int rankedCount = 0;
                    int unrankedCount = 0;
                    int localCount = 0;

                    double rankedPp = 0;
                    double unrankedPp = 0;
                    double localPp = 0;

                    for (int i = 0; i < scores.size(); i++) {
                        final Score current = scores.get(i);
                        final ScoreEntry entry = entries.get(i);
                        final Score.Weight weight = new Score.Weight();
                        final double percentage = Math.pow(0.95, i);
                        final double scorePp = current.getPp() * percentage;
                        weight.setPercentage(percentage * 100.0);
                        weight.setPp(scorePp);
                        current.setWeight(weight);

                        if (current.getRanked() != null
                                && current.getRanked()
                                && ("RANKED".equalsIgnoreCase(current.getBeatmap().getStatus())
                                || "APPROVED".equalsIgnoreCase(current.getBeatmap().getStatus()))
                                && Scores.isModsRankable(current)
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

                    final int end = Math.min(scores.size(), endPosition);
                    final List<ScoreEntry> rangedScores = entries.subList(start - 1, end);
                    var filtered = applyFilters(rangedScores, filters, null);

                    return getScoreListUser(u)
                            .thenApply(user -> {
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
                                putScoreListHeaders(ctx, user, filtered.entries());
                                return new DerankedBestData(
                                        user, filtered.entries(), newRank, newPp,
                                        newPp - ppCounts.ranked() - ppCounts.unranked() - ppCounts.local(),
                                        scoreCounts, ppCounts, filtered.filters(), filtered.extraTitle()
                                );
                            });
                })
                .thenCompose(data -> ImageResponse.respond(
                        ctx, data, verbosity::project, this::renderDerankedBest, renderer.getRenderExecutor()
                ))
        );
    }

    private CompletableFuture<DerankedScores> loadDerankedScores(long userId, ScoreListRange range, List<ScoreFilter> filters) {
        int limit = OsuAPI.MAX_USER_SCORES_LIMIT;
        return executor.enqueueAsync(() -> OsuAPI.getUserScores(tokenManager.getTokenData(), userId, ScoreType.BEST, limit))
                .thenCompose(official -> {
                    var combined = new ArrayList<>(official);
                    CompletableFuture<Void> loaded = CompletableFuture.completedFuture(null);
                    for (var type : List.of(ScoreType.RECENT_PASS, ScoreType.FIRSTS, ScoreType.PINNED)) {
                        loaded = loaded.thenCompose(_ -> executor.enqueueAsync(() ->
                                        OsuAPI.getUserScores(tokenManager.getTokenData(), userId, type, limit))
                                .thenAccept(combined::addAll));
                    }
                    return loaded.thenCompose(_ -> {
                        combined.forEach(Router::ensurePp);
                        ScoreStore.upsertScores(combined);
                        var merged = ScoreStore.getUserBests(userId, limit).stream()
                                .map(ScoreRecord::toScore)
                                .sorted(Comparator.comparing(Score::getPp).reversed())
                                .toList();
                        if (range.start() > merged.size()) throw new ApiException(ErrorCode.NO_SCORE_FOUND);
                        boolean needReplayOrCombo = filters.stream().map(ScoreFilter::getField).anyMatch(field ->
                                field == ScoreFilter.Field.UR || field == ScoreFilter.Field.REPLAY || field == ScoreFilter.Field.FULL_COMBO);
                        return fillDerankedMetadata(merged, official, range.start(), range.end(), needReplayOrCombo)
                                .thenApply(_ -> new DerankedScores(official.stream().map(Score::getPp).toList(), merged));
                    });
                });
    }

    private CompletableFuture<Void> fillDerankedMetadata(
            List<Score> scores, List<Score> official, int start, int end, boolean needReplayOrCombo
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
            // SQLite score records omit the replay and perfect-combo flags.
            if (displayed && needReplayOrCombo) {
                futures.add(router.getScore(score.getId()).thenAccept(original -> {
                    if (original != null) {
                        score.setHasReplay(original.getHasReplay());
                        score.setIsPerfectCombo(original.getIsPerfectCombo());
                    }
                }));
            }
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

    public void getUserBeatmapScores(@NotNull Context ctx) {
        final long userId = requirePathLong(ctx, "userId");
        final long beatmapId = requirePathLong(ctx, "beatmapId");
        final var verbosity = ResponseVerbosity.forJson(ctx);

        ctx.future(() -> executor.enqueueAsync(() -> OsuAPI.getUser(tokenManager.getTokenData(), userId))
                .thenCompose(user -> {
                    if (user == null) throw new ApiException(ErrorCode.NO_USER_FOUND);

                    return executor.enqueueAsync(() -> OsuAPI.getUserBeatmapScores(tokenManager.getTokenData(), userId, beatmapId))
                            .thenCompose(scoresBrief -> {
                                final var futures = Optional.ofNullable(scoresBrief).orElseGet(List::of).stream()
                                        .map(Score::getId)
                                        .map(router::getScore)
                                        .toList();

                                return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                                        .thenApply(_ -> futures.stream()
                                                .map(CompletableFuture::join)
                                                .filter(Objects::nonNull)
                                                .toList())
                                        .thenApply(scores -> {
                                            var stored = ScoreStore.getScoresForUsers(beatmapId, List.of(userId), ScoreStore.SortType.SCORE)
                                                    .stream().map(ScoreRecord::toScore).toList();
                                            if (scores.isEmpty() && stored.isEmpty()) {
                                                throw new ApiException(ErrorCode.NO_SCORE_FOUND);
                                            }
                                            var metadataScores = scores.isEmpty() ? List.of(stored.getFirst()) : scores;
                                            router.enrichScoreFields(metadataScores, false);
                                            var reference = metadataScores.getFirst();
                                            for (Score score : stored) {
                                                score.setBeatmap(reference.getBeatmap());
                                                score.setBeatmapset(reference.getBeatmapset());
                                            }

                                            for (Score score : scores) {
                                                Router.ensurePp(score);
                                            }

                                            ScoreStore.upsertScores(scores);

                                            var merged = mergeBeatmapScores(scores, stored);
                                            if (merged.isEmpty()) throw new ApiException(ErrorCode.NO_SCORE_FOUND);
                                            ctx.header("X-Beatmap-Id", String.valueOf(beatmapId));
                                            ctx.header("X-Beatmapset-Id", String.valueOf(reference.getBeatmapset().getId()));
                                            return prepareScoreList(ctx, user, new FilteredScores(numberedScores(merged, 1), List.of(), null), "Beatmap " + beatmapId);
                                        });
                            });
                })
                .thenCompose(data -> ImageResponse.respond(
                        ctx, data, verbosity::project, this::renderScoreList, renderer.getRenderExecutor()))
        );
    }

    public void getReworkedBests(@NotNull Context ctx) {
        final long userId = requirePathLong(ctx, "userId");
        final String calculatorVersion = requireString(ctx, "version");
        final var verbosity = ResponseVerbosity.forJson(ctx);

        final PpCalculatorService calculator = router.getPpCalculatorServices().get(calculatorVersion);

        if (calculator == null) {
            throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, "Calculator '%s' not found".formatted(calculatorVersion));
        }

        ctx.future(() -> executor.enqueueAsync(() -> OsuAPI.getUser(tokenManager.getTokenData(), userId))
                .thenCompose(user -> {
                    if (user == null) {
                        throw new ApiException(ErrorCode.NO_USER_FOUND);
                    }

                    return calculator.enqueueCalculation(user.getId())
                            .thenApply(calc -> {
                                ArrayList<ReworkedScore> scores = new ArrayList<>(200);
                                for (CalculatedScore score : calc.scores()) {
                                    try {
                                        scores.add(toReworkedScore(score));
                                    } catch (ParseException | AnalyzeException e) {
                                        throw new RuntimeException(e);
                                    }
                                }
                                scores.sort(Comparator.comparingDouble(ReworkedScore::localPp).reversed());
                                return new ReworkedBestData(user, scores, calc.livePp(), calc.localPp(), calc.playcountPp(), calc.pp(), calculatorVersion);
                            })
                            .thenCompose(data -> ImageResponse.respond(
                                    ctx, data, verbosity::project, this::renderReworkedBest, renderer.getRenderExecutor()
                            ));
                }));
    }

    private ReworkedScore toReworkedScore(CalculatedScore score) throws ParseException, AnalyzeException {
        final BeatmapExtended beatmap = CacheService.getBeatmapJsonCache(score.beatmapId())
                .orElseGet(() -> {
                    final var bm = OsuAPI.getBeatmap(tokenManager.getTokenData(), score.beatmapId());
                    tryCache(bm);
                    return bm;
                });

        if (beatmap == null) {
            throw new ApiException(ErrorCode.BEATMAP_FETCH_FAILED);
        }

        final Beatmapset beatmapset = CacheService.getBeatmapsetJsonCache(beatmap.getBeatmapsetId())
                .orElseGet(() -> {
                    final var set = OsuAPI.getBeatmapset(tokenManager.getTokenData(), beatmap.getBeatmapsetId());
                    tryCache(set);
                    return set;
                });

        final List<Mod> modList = score.mods().stream().map(s -> {
            var mod = new Mod();
            mod.setAcronym(s);
            return mod;
        }).toList();

        final Path beatmapPath = CacheService.getBeatmapPath(beatmap.getId());
        final OsuBeatmap osuBeatmap = BeatmapParser.parseBeatmap(beatmapPath);

        DiffSpec diffSpec = OsuParser.getDiffSpecForMods(osuBeatmap, modList);

        return new ReworkedScore(
                beatmap, beatmapset, score.liveId(), score.rank(), score.combo(), score.accuracy(),
                score.missCount(), score.count100(), score.count50(), score.count300(), score.mods(),
                diffSpec.getStar(), score.livePp(), score.localSr(), score.localPp(), score.weight(),
                score.endedAt(), score.performanceBreakdown(), score.positionChange()
        );
    }

    record RecentBestData(List<ScoreEntry> scores, Double ppChange) {
    }

    private record DerankedScores(List<Double> officialPp, List<Score> merged) {
    }

    private record ScoreListData(UserExtended user, List<ScoreEntry> scores,
                                 List<String> filters, String title, String extraTitle) {
    }

    private record DerankedBestData(
            UserExtended user, List<ScoreEntry> scores, Long newGlobalRank, double newPp, double bonusPp,
            ScoreCategoryTotals<Integer> counts, ScoreCategoryTotals<Double> ppCounts, List<String> filters,
            String extraTitle
    ) {
    }

    public record ReworkedBestData(
            UserExtended user, List<ReworkedScore> scores, double livePp, double localPp, double playcountPp,
            PerformanceBreakdown ppTypes, String ppVersion
    ) {
    }

    private record FilteredScores(List<ScoreEntry> entries, List<String> filters, String extraTitle) {
    }

    private record UserInfoData(UserExtended user, List<Score> topScores) {
    }

    public record UserLookupBody(
            @SerializedName("user_name") String userName,
            @SerializedName("user_names") List<String> userNames
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
