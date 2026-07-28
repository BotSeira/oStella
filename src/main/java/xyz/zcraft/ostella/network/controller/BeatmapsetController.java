package xyz.zcraft.ostella.network.controller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.javalin.http.Context;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import xyz.zcraft.ostella.data.SearchResultItem;
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
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static xyz.zcraft.ostella.util.RequestUtil.*;

public class BeatmapsetController {
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

    private void lookupBeatmapsetFromCurrentRoom(@NonNull Context context) {
        final String auth = context.header("Authorization");

        if (auth == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }

        context.future(() -> executor
                .enqueueAsync(() -> OsuAPI.getCurrentRoom(auth))
                .thenCompose(room -> {
                    if (room == null) throw new ApiException(ErrorCode.NO_ROOM_FOUND);
                    if (room.getCurrentPlaylistItem() == null) throw new ApiException(ErrorCode.NO_BEATMAPSET_FOUND);
                    return executor.enqueueAsync(() -> OsuAPI.getBeatmapsetFromBeatmap(tokenManager.getTokenData(), room.getCurrentPlaylistItem().getBeatmapId()));
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

    public void renderBeatmapsetById(@NotNull Context context) {
        final long ms = requirePathLong(context, "beatmapsetId");

        final String header = context.header("Accept");
        if (header != null && header.contains("application/json")) {
            context.future(
                    () -> executor.enqueueAsync(() -> OsuAPI.getBeatmapset(tokenManager.getTokenData(), ms))
                            .thenAccept(beatmapset -> putResult(context, beatmapset))
            );
        } else {
            context.future(() -> executor.enqueueAsync(() -> OsuAPI.getBeatmapset(tokenManager.getTokenData(), ms))
                    .thenApplyAsync(beatmapset -> finalizeBeatmapset(beatmapset, context), renderer.getRenderExecutor())
                    .thenAccept(bytes -> context.status(200).result(bytes)));
        }
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

    private byte[] finalizeBeatmapset(Beatmapset beatmapset, Context context) {
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

        return renderer.renderBeatmapset(beatmapset);
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
        context.contentType("image/png");
        context.future(() -> executor.enqueueAsync(() -> OsuAPI.getBeatmapset(tokenManager.getTokenData(), ms))
                .thenAccept(beatmapset -> {
                    if (beatmapset != null) {
                        context.header("X-Beatmapset-Id", beatmapset.getId().toString());
                        final String cover = beatmapset.getCovers().getCover();
                        try {
                            context.result(URI.create(cover).toURL().openStream());
                        } catch (IOException e) {
                            context.status(500).result(Response.error("Failed to parse bg url", ErrorCode.IMAGE_FETCH_FAILED).toString());
                        }
                    }
                }));
    }

    private static final Gson GSON = new Gson();

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
