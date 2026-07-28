package xyz.zcraft.ostella.network.controller;

import com.google.gson.*;
import com.google.gson.annotations.SerializedName;
import io.javalin.http.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.zcraft.ostella.data.ScoreType;
import xyz.zcraft.ostella.exception.ApiException;
import xyz.zcraft.ostella.network.*;
import xyz.zcraft.ostella.service.AsyncService;
import xyz.zcraft.ostella.service.RenderService;
import xyz.zcraft.ostella.util.TokenManager;
import xyz.zcraft.osu.model.Score;
import xyz.zcraft.osu.model.User;

import java.util.ArrayList;
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
        final int n = requireInt(context, "n");
        final boolean fail = requireBoolean(context, "fail", false);

        final ScoreType type = fail ? ScoreType.RECENT : ScoreType.RECENT_PASS;

        context.future(() -> executor.enqueueAsync(() -> OsuAPI.getUserScores(
                        tokenManager.getTokenData(), u, type, n)
                )
                .thenCompose(scores -> executor.enqueueAsync(() -> OsuAPI.getUser(tokenManager.getTokenData(), u))
                        .thenApplyAsync(user -> {
                            if (user == null) {
                                throw new ApiException(ErrorCode.NO_USER_FOUND, "No user found");
                            }
                            context.header("X-User-Id", String.valueOf(user.getId()));
                            context.header("X-Score-Ids", scores.stream().map(Score::getId).map(String::valueOf).collect(Collectors.joining(",")));

                            for (Score score : scores) {
                                router.ensurePp(score);
                            }

                            return renderer.renderScores(user, scores, fail ? ScoreType.RECENT : ScoreType.RECENT_PASS);
                        }, renderer.getRenderExecutor()))
                .thenAccept(bytes -> context.status(200).result(bytes)));
    }

    public void getBestOfN(@NotNull Context context) {
        final long u = requirePathLong(context, "userId");
        final int n = requireInt(context, "n");

        context.future(() -> executor.enqueueAsync(() -> OsuAPI.getUserScores(
                        tokenManager.getTokenData(), u, ScoreType.BEST, n
                ))
                .thenCompose(scores -> {
                    if (scores == null || scores.isEmpty()) throw new ApiException(ErrorCode.NO_SCORE_FOUND);
                    return executor.enqueueAsync(() -> OsuAPI.getUser(tokenManager.getTokenData(), u))
                            .thenApplyAsync(user -> {
                                if (user == null) throw new ApiException(ErrorCode.NO_USER_FOUND);
                                context.header("X-User-Id", String.valueOf(user.getId()));
                                context.header("X-Score-Ids", scores.stream().map(Score::getId).map(String::valueOf).collect(Collectors.joining(",")));
                                return renderer.renderScores(user, scores, ScoreType.BEST);
                            }, renderer.getRenderExecutor());
                })
                .thenAccept(bytes -> context.status(200).result(bytes)));
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
}
