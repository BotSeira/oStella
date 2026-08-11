package xyz.zcraft.ostella.network.controller;

import com.google.gson.JsonObject;
import io.javalin.http.Context;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
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
import xyz.zcraft.osu.model.BeatmapExtended;
import xyz.zcraft.osu.model.Mod;
import xyz.zcraft.osu.model.MultiplayerRoom;
import xyz.zcraft.osu.model.Score;
import xyz.zcraft.osu.parser.BeatmapAnalyzer;
import xyz.zcraft.osu.parser.BeatmapParser;
import xyz.zcraft.osu.parser.OsuParser;
import xyz.zcraft.osu.parser.data.beatmap.DiffSpec;
import xyz.zcraft.osu.parser.data.beatmap.OsuBeatmap;
import xyz.zcraft.osu.parser.data.beatmap.WindowDifficulty;
import xyz.zcraft.osu.parser.exception.AnalyzeException;
import xyz.zcraft.osu.parser.exception.ParseException;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static xyz.zcraft.ostella.util.RequestUtil.*;

public class BeatmapController {
    final RenderService renderer;
    final AsyncService executor;
    final TokenManager tokenManager;
    final Router router;

    public BeatmapController(Router router) {
        this.router = router;
        this.renderer = router.renderer;
        this.tokenManager = router.tokenManager;
        this.executor = router.executor;
    }

    public void lookupBeatmap(@NotNull Context context) {
        if (context.queryParam("of") != null) {
            lookupBeatmapOfRefAsync(context);
        } else if (context.queryParam("ms") != null) {
            lookupBeatmapOfSetAsync(context);
        } else {
            lookupBeatmapOfIdAsync(context);
        }
    }

    private void lookupBeatmapOfSetAsync(@NotNull Context context) {
        final long ms = requireLong(context, "ms");
        final int i = requireInt(context, "i");

        context.future(() -> executor
                .enqueueAsync(() -> OsuAPI.getBeatmapset(tokenManager.getTokenData(), ms))
                .thenApply(beatmapset -> {
                    if (beatmapset == null)
                        throw new ApiException(ErrorCode.NO_BEATMAPSET_FOUND, "No beatmapset found");
                    final List<BeatmapExtended> beatmaps = beatmapset.getBeatmaps();
                    beatmaps.sort(Comparator.comparingDouble(BeatmapExtended::getDifficultyRating));
                    final BeatmapExtended beatmapExtended = beatmaps.get(i - 1);
                    beatmapExtended.setBeatmapset(beatmapset);
                    return beatmapExtended;
                })
                .thenAccept(beatmapExtended -> context.status(200).result(
                        new Response(true, "Success", beatmapLookupData(beatmapExtended)).toString()
                )));
    }

    private void lookupBeatmapOfRefAsync(@NotNull Context context) {
        final String of = requireStringFrom(context, "of", "rs", "bo", "mp", "rp");

        if ("mp".equals(of)) {
            lookupBeatmapFromSomeRoom(context);
        } else {
            lookupBeatmapFromSomeScore(context, of);
        }
    }

    private void lookupBeatmapFromSomeRoom(@NotNull Context context) {
        final String auth = context.header("Authorization");

        if (auth == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }

        context.future(() -> executor
                .enqueueAsync(() -> OsuAPI.getCurrentRoom(auth))
                .thenApply(room -> {
                    if (room == null)
                        throw new ApiException(ErrorCode.NO_ROOM_FOUND, "No multiplayer room found");

                    final MultiplayerRoom.CurrentPlaylistItem currentPlaylistItem = room.getCurrentPlaylistItem();
                    if (currentPlaylistItem == null || currentPlaylistItem.getBeatmap() == null)
                        throw new ApiException(ErrorCode.NO_BEATMAP_FOUND, "No beatmap found for current multiplayer room");

                    return currentPlaylistItem.getBeatmap();
                })
                .thenAccept(beatmap -> context.status(200).result(
                        new Response(true, "Success", beatmapLookupData(beatmap)).toString()
                )));
    }

    private void lookupBeatmapFromSomeScore(@NonNull Context context, @NotNull String of) {
        final int i = requirePositiveInt(context, "i");
        final long u = requireLong(context, "u");

        final ScoreType type = switch (of.toLowerCase()) {
            case "rs" -> ScoreType.RECENT;
            case "rp" -> ScoreType.RECENT_PASS;
            case "bo" -> ScoreType.BEST;
            default -> throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, "Invalid score type: " + of);
        };

        context.future(() -> executor.enqueueAsync(() -> OsuAPI.getUserScores(tokenManager.getTokenData(), u, type, i))
                .thenCompose(scores -> {
                    if (scores == null || scores.isEmpty())
                        throw new ApiException(ErrorCode.NO_SCORE_FOUND, "No scores found");

                    final Score score = scores.get(i - 1);

                    final var beatmapId = score.getBeatmap().getId();
                    final var beatmapsetId = score.getBeatmapset().getId();

                    context.header("X-Beatmap-Id", String.valueOf(beatmapId));
                    context.header("X-Beatmapset-Id", String.valueOf(beatmapsetId));

                    return executor
                            .enqueueAsync(() -> OsuAPI.getBeatmapset(tokenManager.getTokenData(), beatmapsetId))
                            .thenApply(beatmapset -> {
                                if (beatmapset == null)
                                    throw new ApiException(ErrorCode.NO_BEATMAPSET_FOUND, "No beatmapset found");
                                final BeatmapExtended beatmapExtended = beatmapset.getBeatmaps()
                                        .stream().filter(b -> Objects.equals(b.getId(), beatmapId))
                                        .findFirst().orElseThrow(() -> new ApiException(ErrorCode.NO_BEATMAP_FOUND, "No beatmap found"));
                                beatmapExtended.setBeatmapset(beatmapset);
                                return beatmapExtended;
                            });
                })
                .thenAccept(beatmap -> context.status(200).result(
                        new Response(true, "Success", beatmapLookupData(beatmap)).toString()
                )));
    }

    private JsonObject beatmapLookupData(BeatmapExtended beatmap) {
        final JsonObject data = new JsonObject();
        data.addProperty("beatmap_id", beatmap.getId());
        data.addProperty("beatmapset_id", beatmap.getBeatmapsetId());
        return data;
    }

    private void lookupBeatmapOfIdAsync(@NotNull Context context) {
        final long m = requireLong(context, "m");

        context.future(() -> executor
                .enqueueAsync(() -> OsuAPI.getBeatmapsetFromBeatmap(tokenManager.getTokenData(), m))
                .thenApply(beatmapset -> {
                    if (beatmapset == null)
                        throw new ApiException(ErrorCode.NO_BEATMAPSET_FOUND, "No beatmapset found");
                    final BeatmapExtended beatmapExtended = beatmapset.getBeatmaps()
                            .stream()
                            .filter(b -> Objects.equals(b.getId(), m))
                            .findFirst()
                            .orElseThrow(() -> new ApiException(ErrorCode.NO_BEATMAP_FOUND, "No beatmap found"));
                    beatmapExtended.setBeatmapset(beatmapset);
                    return beatmapExtended;
                })
                .thenAccept(beatmapExtended -> context.status(200).result(
                        new Response(true, "Success", beatmapLookupData(beatmapExtended)).toString()
                )));
    }

    public void renderBeatmapById(@NotNull Context context) {
        final String mod = optionalString(context, "mod");
        final long m = requirePathLong(context, "beatmapId");

        context.future(() -> executor
                .enqueueAsync(() -> OsuAPI.getBeatmapsetFromBeatmap(tokenManager.getTokenData(), m))
                .thenApply(beatmapset -> {
                    if (beatmapset == null)
                        throw new ApiException(ErrorCode.NO_BEATMAPSET_FOUND, "No beatmapset found");
                    final BeatmapExtended beatmapExtended = beatmapset.getBeatmaps()
                            .stream()
                            .filter(b -> Objects.equals(b.getId(), m))
                            .findFirst()
                            .orElseThrow(() -> new ApiException(ErrorCode.NO_BEATMAP_FOUND, "No beatmap found"));
                    beatmapExtended.setBeatmapset(beatmapset);
                    context.header("X-Beatmap-Id", String.valueOf(beatmapExtended.getId()));
                    context.header("X-Beatmapset-Id", String.valueOf(beatmapExtended.getBeatmapsetId()));

                    return beatmapExtended;
                })
                .thenApplyAsync(beatmap -> {
                    try {
                        final Path beatmapPath = CacheService.getBeatmapPath(beatmap.getId());
                        final OsuBeatmap osuBeatmap = BeatmapParser.parseBeatmap(beatmapPath);
                        DiffSpec diffSpec = OsuParser.getDiffSpecForMap(osuBeatmap, mod);

                        final Mod e = new Mod();
                        e.setAcronym("V2");
                        diffSpec.getMods().add(e);

                        final List<Double> diff = BeatmapAnalyzer.getWindowDifficulties(osuBeatmap, Duration.ofSeconds((long) Math.max(3, (beatmap.getTotalLength() / 50.0))))
                                .stream()
                                .map(WindowDifficulty::pp)
                                .map(pp -> pp * pp)
                                .toList();

                        return renderer.renderBeatmap(beatmap, diffSpec, diff);
                    } catch (ParseException e) {
                        throw new ApiException(ErrorCode.BEATMAP_PARSE_FAILED, e);
                    } catch (AnalyzeException e) {
                        throw new ApiException(ErrorCode.SCORE_PARSE_FAILED, e);
                    }
                }, renderer.getRenderExecutor())
                .thenAccept(bytes -> context.status(200).result(bytes)));
    }

    public void getBackground(@NotNull Context context) {
        final long m = requirePathLong(context, "beatmapId");

        context.future(() ->
                executor.enqueueAsync(() -> CacheService.getBeatmapPath(m))
                        .thenApply(p -> {
                            try {
                                return BeatmapParser.parseBeatmap(p);
                            } catch (ParseException e) {
                                throw new ApiException(ErrorCode.BEATMAP_PARSE_FAILED, e);
                            }
                        })
                        .thenApply(b -> {
                            final OsuBeatmap.Event.BackgroundEvent bg = (OsuBeatmap.Event.BackgroundEvent) b.getBgAndVideoEvents().stream().filter(e -> e.getType() == OsuBeatmap.Event.Type.BACKGROUND)
                                    .findFirst()
                                    .orElseThrow(() -> new ApiException(ErrorCode.NO_BACKGROUND_FOUND, "No background found"));

                            context.header("X-Beatmap-Id", String.valueOf(b.getBeatmapId()));
                            context.header("X-Beatmapset-Id", String.valueOf(b.getBeatmapSetId()));

                            String fileName = bg.getFileName();
                            if (fileName.startsWith("\"") && fileName.endsWith("\"")) {
                                fileName = fileName.substring(1, fileName.length() - 1);
                            }

                            CacheService.cacheBeatmapsetFile(b.getBeatmapSetId());

                            return CacheService.extractFile(b.getBeatmapSetId(), fileName)
                                    .orElseThrow(() -> new ApiException(ErrorCode.NO_BACKGROUND_FOUND, "No background found"));
                        })
                        .thenAccept(bytes -> context.status(200).result(bytes))
        );
    }
}
