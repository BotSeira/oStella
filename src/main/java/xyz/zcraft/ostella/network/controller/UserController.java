package xyz.zcraft.ostella.network.controller;

import com.google.gson.*;
import com.google.gson.annotations.SerializedName;
import io.javalin.http.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.zcraft.ostella.data.ScoreType;
import xyz.zcraft.ostella.data.ScoreFilter;
import xyz.zcraft.ostella.exception.ApiException;
import xyz.zcraft.ostella.network.*;
import xyz.zcraft.ostella.service.AsyncService;
import xyz.zcraft.ostella.service.RenderService;
import xyz.zcraft.ostella.util.TokenManager;
import xyz.zcraft.osu.model.Mod;
import xyz.zcraft.osu.model.Score;
import xyz.zcraft.osu.model.User;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import static xyz.zcraft.ostella.util.RequestUtil.*;

public class UserController {
    private static final Logger LOG = LogManager.getLogger(UserController.class);
    private static final Gson GSON = new Gson();

    public final RenderService renderer;
    public final AsyncService executor;
    public final TokenManager tokenManager;
    public final Router router;

    public UserController(Router router) {
        this.router = router;
        this.renderer = router.renderer;
        this.executor = router.executor;
        this.tokenManager = router.tokenManager;
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
        final String auth = context.header("Authorization");

        if (auth == null) {
            context.status(401)
                    .result(Response.error("Missing Authorization header", ErrorCode.UNAUTHORIZED).toString());
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

    public void getRecentScores(@NotNull Context context) {
        final long u = requirePathLong(context, "userId");
        final int n = requireScoreListLimit(context);
        final boolean fail = requireBoolean(context, "fail", false);
        final List<ScoreFilter> filters = requireScoreFilters(context);

        final ScoreType type = fail ? ScoreType.RECENT : ScoreType.RECENT_PASS;
        context.future(() -> executor.enqueueAsync(() -> OsuAPI.getUserScores(
                        tokenManager.getTokenData(), u, type, n)
                )
                .thenCompose(scores -> executor.enqueueAsync(() -> OsuAPI.getUser(tokenManager.getTokenData(), u))
                        .thenApplyAsync(user -> {
                            if (user == null) {
                                throw new ApiException(ErrorCode.NO_USER_FOUND, "No user found");
                            }
                            for (Score score : scores) {
                                router.ensurePp(score);
                            }

                            FilteredScores filteredScores = applyFilters(scores, filters);
                            context.header("X-User-Id", String.valueOf(user.getId()));
                            context.header("X-Score-Ids", filteredScores.scores().stream().map(Score::getId).map(String::valueOf).collect(Collectors.joining(",")));

                            return renderer.renderScores(
                                    user,
                                    filteredScores.scores(),
                                    type,
                                    filterLabels(filters),
                                    filteredScores.originalPositions()
                            );
                        }, renderer.getRenderExecutor()))
                .thenAccept(bytes -> context.status(200).result(bytes)));
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

    public void getBestOfN(@NotNull Context context) {
        final long u = requirePathLong(context, "userId");
        final int n = requireScoreListLimit(context);
        final List<ScoreFilter> filters = requireScoreFilters(context);
        context.future(() -> executor.enqueueAsync(() -> OsuAPI.getUserScores(
                        tokenManager.getTokenData(), u, ScoreType.BEST, n
                ))
                .thenCompose(scores -> {
                    if (scores == null || scores.isEmpty()) throw new ApiException(ErrorCode.NO_SCORE_FOUND);
                    return executor.enqueueAsync(() -> OsuAPI.getUser(tokenManager.getTokenData(), u))
                            .thenApplyAsync(user -> {
                                if (user == null) throw new ApiException(ErrorCode.NO_USER_FOUND);
                                for (Score score : scores) {
                                    router.ensurePp(score);
                                }
                                FilteredScores filteredScores = applyFilters(scores, filters);
                                context.header("X-User-Id", String.valueOf(user.getId()));
                                context.header("X-Score-Ids", filteredScores.scores().stream().map(Score::getId).map(String::valueOf).collect(Collectors.joining(",")));
                                return renderer.renderScores(
                                        user,
                                        filteredScores.scores(),
                                        ScoreType.BEST,
                                        filterLabels(filters),
                                        filteredScores.originalPositions()
                                );
                            }, renderer.getRenderExecutor());
                })
                .thenAccept(bytes -> context.status(200).result(bytes)));
    }

    private static List<ScoreFilter> requireScoreFilters(Context context) {
        try {
            return ScoreFilter.parseList(context.queryParam("filters"));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, e.getMessage(), e);
        }
    }

    private static int requireScoreListLimit(Context context) {
        int limit = requirePositiveInt(context, "n");
        if (limit > OsuAPI.MAX_USER_SCORES_LIMIT) {
            throw new ApiException(
                    ErrorCode.ILLEGAL_ARGUMENT,
                    "Score limit must not exceed " + OsuAPI.MAX_USER_SCORES_LIMIT
            );
        }
        return limit;
    }

    static FilteredScores applyFilters(List<Score> scores, List<ScoreFilter> filters) {
        List<Score> result = new ArrayList<>();
        List<Integer> originalPositions = new ArrayList<>();
        for (int index = 0; index < scores.size(); index++) {
            Score score = scores.get(index);
            if (filters.stream().allMatch(filter -> filter.matches(score))) {
                result.add(score);
                originalPositions.add(index + 1);
            }
        }
        if (!filters.isEmpty() && result.isEmpty()) {
            throw new ApiException(ErrorCode.NO_SCORE_FOUND, "No scores matched the filters");
        }
        return new FilteredScores(List.copyOf(result), List.copyOf(originalPositions));
    }

    private static List<String> filterLabels(List<ScoreFilter> filters) {
        return filters.stream().map(ScoreFilter::displayText).toList();
    }

    record FilteredScores(List<Score> scores, List<Integer> originalPositions) {
    }

    public void getFriends(@NotNull Context context) {
        final String auth = context.header("Authorization");

        if (auth == null) {
            context.status(401)
                    .result(Response.error("Missing Authorization header", ErrorCode.UNAUTHORIZED).toString());
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
                    if (u == null) throw new ApiException(ErrorCode.NO_USER_FOUND, "No user found for the provided username!");
                    return (User) u;
                })
                .thenAccept(u -> context.status(200).result(new Response(true, "Success", GSON.toJsonTree(u)).toString()))
        );
    }

    public record UserLookupBody(
            @SerializedName("user_name") String userName
    ) {
    }

    public record RecentScoresBatchBody(
            @SerializedName("user_ids") List<Long> userIds,
            @SerializedName("limit") int limit,
            @SerializedName("include_fails") boolean includeFails
    ) {}

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
                    score.getMods().stream().map(Mod::getAcronym).collect(Collectors.joining(""))
            );
        }
    }
}
