package xyz.zcraft.ostella.network.controller;

import com.google.gson.*;
import io.javalin.http.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import xyz.zcraft.ostella.data.SearchResultItem;
import xyz.zcraft.ostella.exception.ApiException;
import xyz.zcraft.ostella.network.*;
import xyz.zcraft.ostella.service.AsyncService;
import xyz.zcraft.ostella.service.CacheService;
import xyz.zcraft.ostella.service.RenderService;
import xyz.zcraft.ostella.util.TokenManager;
import xyz.zcraft.osu.model.Beatmap;
import xyz.zcraft.osu.model.BeatmapExtended;
import xyz.zcraft.osu.model.Beatmapset;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import static xyz.zcraft.ostella.util.RequestUtil.*;

public class BeatmapsetController {
    private static final Gson GSON = new Gson();
    private static final Logger LOG = LogManager.getLogger(BeatmapsetController.class);
    public final RenderService renderer;
    public final AsyncService executor;
    public final TokenManager tokenManager;
    public final Router router;

    public BeatmapsetController(Router router) {
        this.router = router;
        this.renderer = router.renderer;
        this.tokenManager = router.tokenManager;
        this.executor = router.executor;
    }

    public void lookupBeatmapset(@NotNull Context context) {
        if (context.queryParam("of") != null) {
            lookupBeatmapsetOfRefAsync(context);
        } else if (context.queryParam("m") != null) {
            lookupBeatmapsetOfMapAsync(context);
        } else {
            lookupBeatmapsetOfIdAsync(context);
        }
    }

    public void getBeatmapsets(@NotNull Context context) {
        final JsonElement body = JsonParser.parseString(context.body());
        final var idArr = body.getAsJsonObject().getAsJsonArray("ids");

        if (idArr == null || idArr.isEmpty()) {
            context.status(400).result(Response.error("Missing 'ids' array in request body", ErrorCode.ILLEGAL_ARGUMENT).toString());
            return;
        }

        context.future(() -> {
            List<CompletableFuture<Beatmapset>> beatmapsetFutures = new ArrayList<>(idArr.size());
            for (JsonElement jsonElement : idArr) {
                final long id = jsonElement.getAsLong();
                beatmapsetFutures.add(
                        CompletableFuture.supplyAsync(
                                () -> CacheService.getBeatmapsetJsonCache(id)
                                        .orElseGet(() -> OsuAPI.getBeatmapset(tokenManager.getTokenData(), id))
                        )
                );
            }

            return CompletableFuture.allOf(beatmapsetFutures.toArray(new CompletableFuture[0]))
                    .thenApply(_ -> {
                        JsonArray resultArr = new JsonArray();
                        for (var future : beatmapsetFutures) {
                            try {
                                final Beatmapset beatmapset = future.join();
                                if (beatmapset == null) {
                                    continue;
                                }
                                CacheService.tryCache(beatmapset);

                                final List<Beatmap> beatmaps = new ArrayList<>(beatmapset.getBeatmaps().size());
                                for (BeatmapExtended beatmap : beatmapset.getBeatmaps()) {
                                    final Beatmap e = convertToShort(beatmap);
                                    beatmaps.add(e);
                                }

                                beatmapset.setConverts(null);
                                beatmapset.setRecentFavourites(null);
                                beatmapset.setRelatedUsers(null);

                                final var obj = GSON.toJsonTree(beatmapset).getAsJsonObject();
                                obj.add("beatmaps", GSON.toJsonTree(beatmaps));

                                resultArr.add(obj);
                            } catch (CompletionException e) {
                                if (e.getCause() instanceof ApiException apiEx) {
                                    if (apiEx.getErrorCode() == ErrorCode.NO_BEATMAPSET_FOUND || apiEx.getErrorCode() == ErrorCode.BEATMAPSET_FETCH_FAILED) {
                                        LOG.warn("Beatmapset not found for one of the requested ids: {}", apiEx.getMessage());
                                    }
                                }
                                LOG.error("Error fetching beatmapset data", e);
                            }
                        }
                        return resultArr;
                    }).thenAccept(usersArr -> context.status(200).result(new Response(true, "Success", usersArr).toString()));
        });
    }

    private @NonNull Beatmap convertToShort(BeatmapExtended beatmap) {
        final Beatmap e = new Beatmap();
        e.setBeatmapsetId(beatmap.getBeatmapsetId());
        e.setDifficultyRating(beatmap.getDifficultyRating());
        e.setId(beatmap.getBeatmapsetId());
        e.setMode(beatmap.getMode());
        e.setStatus(beatmap.getStatus());
        e.setTopUserTagIds(beatmap.getTopUserTagIds());
        e.setTotalLength(beatmap.getTotalLength());
        e.setUserId(beatmap.getUserId());
        e.setVersion(beatmap.getVersion());
        return e;
    }

    private void lookupBeatmapsetFromCurrentRoom(@NonNull Context context) {
        final String auth = context.header(Headers.OSU_AUTHORIZATION);

        Long roomId = MultiplayerLookup.roomId(context.queryParam("room"));

        if (roomId == null && auth == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }

        context.future(() -> executor
                .enqueueAsync(() -> roomId == null ? OsuAPI.getCurrentRoom(auth)
                        : OsuAPI.getRoom(tokenManager.getTokenData(), roomId))
                .thenApply(MultiplayerLookup::currentItem)
                .thenCompose(item -> executor.enqueueAsync(() ->
                        OsuAPI.getBeatmapsetFromBeatmap(tokenManager.getTokenData(), item.getBeatmapId())))
                .thenApply(beatmapset -> {
                    if (beatmapset == null) throw new ApiException(ErrorCode.NO_BEATMAPSET_FOUND);
                    return beatmapset;
                })
                .thenAccept(beatmapset -> context.status(200).result(
                        new Response(true, "Success", beatmapsetLookupData(beatmapset)).toString()
                )));
    }

    private void lookupBeatmapsetFromSomeScore(@NonNull Context context) {
        context.future(() -> router.scoreController.getScoreFromRefAsync(context)
                .thenCompose(score -> {
                    if (score == null) throw new ApiException(ErrorCode.NO_SCORE_FOUND);
                    return executor.enqueueAsync(() -> OsuAPI.getBeatmapset(tokenManager.getTokenData(), score.getBeatmapset().getId()));
                })
                .thenAccept(beatmapset -> context.status(200).result(
                        new Response(true, "Success", beatmapsetLookupData(beatmapset)).toString()
                )));
    }

    private void lookupBeatmapsetOfMapAsync(@NotNull Context context) {
        lookupBeatmapsetOfMapAsync(context, requireLong(context, "m"));
    }

    private void lookupBeatmapsetOfMapAsync(@NotNull Context context, long m) {
        context.future(() -> executor.enqueueAsync(() -> OsuAPI.getBeatmapsetFromBeatmap(tokenManager.getTokenData(), m))
                .thenAccept(beatmapset -> context.status(200).result(
                        new Response(true, "Success", beatmapsetLookupData(beatmapset)).toString()
                )));
    }

    private void lookupBeatmapsetOfIdAsync(@NotNull Context context) {
        lookupBeatmapsetOfIdAsync(context, requireLong(context, "ms"));
    }

    public void getBeatmapsetById(@NotNull Context context) {
        final long ms = requirePathLong(context, "beatmapsetId");

        context.future(() -> executor.enqueueAsync(() -> OsuAPI.getBeatmapset(tokenManager.getTokenData(), ms))
                .thenApply(beatmapset -> prepareBeatmapsetResponse(beatmapset, context))
                .thenCompose(beatmapset -> ImageResponse.respond(
                        context, beatmapset, renderer::renderBeatmapset, renderer.getRenderExecutor())));
    }

    private void lookupBeatmapsetOfIdAsync(@NotNull Context context, long ms) {
        context.future(() -> executor.enqueueAsync(() -> OsuAPI.getBeatmapset(tokenManager.getTokenData(), ms))
                .thenAccept(beatmapset -> context.status(200).result(
                        new Response(true, "Success", beatmapsetLookupData(beatmapset)).toString()
                )));
    }

    private JsonObject beatmapsetLookupData(Beatmapset beatmapset) {
        if (beatmapset == null) throw new ApiException(ErrorCode.NO_BEATMAPSET_FOUND);

        beatmapset.getBeatmaps().sort(Comparator.comparingDouble(Beatmap::getDifficultyRating));
        final JsonObject data = new JsonObject();
        data.addProperty("beatmapset_id", beatmapset.getId());

        final JsonArray beatmapIds = new JsonArray();
        for (BeatmapExtended beatmap : beatmapset.getBeatmaps()) {
            beatmapIds.add(beatmap.getId());
        }

        data.add("beatmap_ids", beatmapIds);
        return data;
    }

    private void lookupBeatmapsetOfRefAsync(@NotNull Context context) {
        final String of = requireString(context, "of");
        if ("mp".equals(of)) {
            lookupBeatmapsetFromCurrentRoom(context);
        } else {
            lookupBeatmapsetFromSomeScore(context);
        }
    }

    private Beatmapset prepareBeatmapsetResponse(Beatmapset beatmapset, Context context) {
        if (beatmapset == null) throw new ApiException(ErrorCode.NO_BEATMAPSET_FOUND);
        beatmapset.getBeatmaps().sort(Comparator.comparingDouble(Beatmap::getDifficultyRating));
        context.header("X-Beatmapset-Id", beatmapset.getId().toString())
                .header("X-Beatmap-Ids", beatmapset.getBeatmaps().stream()
                        .map(BeatmapExtended::getId)
                        .map(String::valueOf)
                        .collect(Collectors.joining(",")))
                .header("X-Beatmap-Stars", beatmapset.getBeatmaps().stream()
                        .map(BeatmapExtended::getDifficultyRating)
                        .map(d -> String.format("%.2f", d))
                        .collect(Collectors.joining(",")));

        CacheService.tryCache(beatmapset);
        return beatmapset;
    }

    public void downloadBeatmapset(@NotNull Context context) {
        final long ms = requirePathLong(context, "beatmapsetId");
        context.future(() -> executor.enqueueAsync(() -> {
            context.contentType("application/zip");
            context.header("Content-Disposition", "attachment; filename=\"" + ms + ".osz\"");
            try {
                CacheService.extractBeatmapset(ms, context.outputStream());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return null;
        }));
    }

    public void getBeatmapsetBg(@NotNull Context context) {
        final long ms = requirePathLong(context, "beatmapsetId");
        final boolean json = ImageResponse.wantsJson(context);
        context.future(() -> executor.enqueueAsync(() -> OsuAPI.getBeatmapset(tokenManager.getTokenData(), ms))
                .thenAccept(beatmapset -> {
                    if (beatmapset == null) throw new ApiException(ErrorCode.NO_BEATMAPSET_FOUND);
                    context.header("X-Beatmapset-Id", beatmapset.getId().toString());
                    final String cover = beatmapset.getCovers().getCover();
                    if (json) {
                        putResult(context, java.util.Map.of("beatmapset_id", ms, "url", cover));
                        return;
                    }
                    try {
                        var connection = URI.create(cover).toURL().openConnection();
                        String contentType = connection.getContentType();
                        context.contentType(contentType == null ? "application/octet-stream" : contentType)
                                .result(connection.getInputStream());
                    } catch (IOException e) {
                        context.status(500).contentType("application/json").result(Response.error("Failed to parse bg url", ErrorCode.IMAGE_FETCH_FAILED).toString());
                    }
                }));
    }

    public void searchBeatmapset(@NotNull Context context) {
        final String query = requireString(context, "q");

        context.future(() -> executor.enqueueAsync(() -> OsuAPI.searchBeatmapset(tokenManager.getTokenData(), query))
                .thenApply(result -> {
                    if (result == null || result.isEmpty()) throw new ApiException(ErrorCode.NO_BEATMAPSET_FOUND);
                    final List<SearchResultItem> list = result.stream().map(SearchResultItem::fromBeatmapset).toList();
                    final var ids = list.stream().map(SearchResultItem::beatmapsetId).map(String::valueOf).toList();
                    context.header("X-Beatmapset-Ids", String.join(",", ids));
                    return list;
                })
                .thenAccept(
                        result -> context.status(200).result(
                                new Response(true, "Query successful", GSON.toJsonTree(result)).toString()
                        )
                ));
    }
}
