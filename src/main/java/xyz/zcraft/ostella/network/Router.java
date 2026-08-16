package xyz.zcraft.ostella.network;

import com.google.gson.*;
import io.javalin.http.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.zcraft.ostella.config.AppConfig;
import xyz.zcraft.ostella.data.ScoreId;
import xyz.zcraft.ostella.cache.CacheControlRequest;
import xyz.zcraft.ostella.cache.CacheControlResult;
import xyz.zcraft.ostella.exception.ApiException;
import xyz.zcraft.ostella.network.controller.*;
import xyz.zcraft.ostella.service.AsyncService;
import xyz.zcraft.ostella.service.CacheService;
import xyz.zcraft.ostella.service.RenderService;
import xyz.zcraft.ostella.service.ReplayService;
import xyz.zcraft.ostella.util.TokenManager;
import xyz.zcraft.ostella.util.VersionInfo;
import xyz.zcraft.osu.model.*;
import xyz.zcraft.osu.parser.BeatmapParser;
import xyz.zcraft.osu.parser.OsuParser;
import xyz.zcraft.osu.parser.data.beatmap.OsuBeatmap;
import xyz.zcraft.osu.parser.exception.AnalyzeException;
import xyz.zcraft.osu.parser.exception.ParseException;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class Router implements Closeable {
    static final Logger LOG = LogManager.getLogger(Router.class);
    public final RenderService renderer;
    public final AsyncService executor;
    public final TokenManager tokenManager;
    public final ReplayService replayService;
    public final AppConfig conf;
    final Gson GSON = new Gson();
    public final ReplayController replayController;
    final BeatmapController beatmapController;
    public final ScoreController scoreController;
    final BeatmapsetController beatmapsetController;
    final LeaderboardController leaderboardController;
    final AnalyzeController analyzeController;
    final MultiplayerController multiplayerController;
    final UserController userController;

    public Router(AppConfig conf, TokenManager tokenManager) throws IOException {
        this.conf = conf;
        this.tokenManager = tokenManager;
        this.executor = new AsyncService(
                conf.ostella().requestPerSecond(),
                conf.ostella().replayRequestIntervalMillis(),
                conf.ostella().replayMaxConcurrent());

        CacheService.initialize(this.executor);

        this.renderer = new RenderService(conf.ostella().renderWorkers());

        this.beatmapController = new BeatmapController(this);
        this.scoreController = new ScoreController(this);
        this.beatmapsetController = new BeatmapsetController(this);
        this.leaderboardController = new LeaderboardController(this);
        this.analyzeController = new AnalyzeController(this);
        this.multiplayerController = new MultiplayerController(this);
        this.userController = new UserController(this);

        this.replayService = new ReplayService(conf);
        this.replayController = new ReplayController(this);

        LOG.info("Router created");
    }

    protected void getServerStatus(@NotNull Context context) {
        context.future(() -> executor
                .enqueueAsync(() -> OsuAPI.isOsuApiHealthy(tokenManager.getTokenData()))
                .thenAccept(r -> context.status(200)
                        .result(new Response(
                                true,
                                "Server is running!",
                                GSON.toJsonTree(Map.of(
                                        "ostella", true,
                                        "ostella_version", VersionInfo.getVersion(),
                                        "osu_api", r
                                ))).toString())));

    }

    public void controlCache(@NotNull Context context) {
        CacheControlRequest request;
        try {
            request = GSON.fromJson(context.body(), CacheControlRequest.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid cache control body", e);
        }
        context.contentType("application/json").result(GSON.toJson(controlCache(request)));
    }

    public CacheControlResult controlCache(CacheControlRequest request) {
        boolean fetch = request != null && "FETCH".equalsIgnoreCase(request.operation());
        CacheControlResult local = fetch
                ? CacheService.fetch(request, tokenManager.getTokenData())
                : CacheService.control(request);
        List<CacheControlResult.CacheNodeResult> nodes = new ArrayList<>(local.nodes());
        Path fetchedPath = fetch && !local.nodes().isEmpty()
                && List.of("FETCHED", "PRESENT").contains(local.nodes().getFirst().status())
                ? CacheService.existingCachePath(request.type(), request.id())
                : null;
        nodes.addAll(fetch
                ? replayService.fetchCacheWorkers(request, fetchedPath)
                : replayService.controlCacheWorkers(request));
        return new CacheControlResult(local.operation(), local.type(), local.id(), nodes);
    }

    public void ensurePp(Score score) {
        if (score.getPp() == null) {
            try {
                final Path beatmapPath = CacheService.getBeatmapPath(score.getBeatmap().getId());
                final OsuBeatmap osuBeatmap = BeatmapParser.parseBeatmap(beatmapPath);
                ensurePp(score, osuBeatmap);
            } catch (ParseException e) {
                LOG.error("Failed to estimate pp for score id: {}", score.getId(), e);
            }
        }
    }

    public void ensurePp(Score score, OsuBeatmap osuBeatmap) {
        if (score.getPp() == null) {
            try {
                score.setPp(OsuParser.estimatePp(score, osuBeatmap));
            } catch (AnalyzeException e) {
                LOG.error("Failed to estimate pp for score id: {}", score.getId(), e);
            }
        }
    }

    protected void getDaily(@NotNull Context context) {
        context.future(() -> executor.enqueueAsync(() -> OsuAPI.getRooms(tokenManager.getTokenData()))
                .thenApply(rooms -> {
                    if (rooms == null || rooms.isEmpty()) {
                        throw new ApiException(ErrorCode.NO_ROOM_FOUND, "No rooms found");
                    }
                    return rooms.stream().filter(room -> Objects.equals(room.getCategory(), "daily_challenge")).findFirst()
                            .orElseThrow(() -> new ApiException(ErrorCode.NO_ROOM_FOUND, "Daily challenge room not found!"));
                })
                .thenApply(room -> {
                    final BeatmapExtended beatmap = room.getCurrentPlaylistItem().getBeatmap();

                    JsonObject data = new JsonObject();
                    data.addProperty("name", room.getName());
                    data.addProperty("participant_count", room.getParticipantCount());
                    data.addProperty("title", beatmap.getBeatmapset().getTitle());
                    data.addProperty("difficulty_rating", beatmap.getDifficultyRating());
                    data.addProperty("version", beatmap.getVersion());

                    StringBuilder modStr = new StringBuilder();
                    for (Mod mod : room.getCurrentPlaylistItem().getRequiredMods()) {
                        modStr.append(mod.getAcronym()).append(" ");
                    }

                    data.addProperty("required_mods", modStr.toString().trim());
                    return data;
                })
                .thenAccept(data -> context.status(200).result(new Response(true, "Success", data).toString())));
    }

    @Override
    public void close() {
        executor.close();
        renderer.close();
        replayService.close();
    }

    public JsonArray getScoresArr(List<Score> scores) {
        JsonArray scoresArr = new JsonArray();
        for (Score score : scores) {
            JsonObject scoreObj = new JsonObject();
            scoreObj.addProperty("username", score.getUser().getUsername());
            scoreObj.addProperty("rank", score.getRank());
            scoreObj.addProperty("accuracy", String.format("%.2f%%", score.getAccuracy() * 100));
            scoreObj.addProperty("pp", String.format("%.2fpp", score.getPp()));
            scoreObj.addProperty("id", ScoreId.format(score));
            scoresArr.add(scoreObj);
        }
        return scoresArr;
    }

    public CompletableFuture<Score> getScore(long id) {
        try {
            final Optional<Score> scoreJsonCache = CacheService.getScoreJsonCache(id);

            if (scoreJsonCache.isPresent()) {
                LOG.debug("Score {} found in cache", id);
                return CompletableFuture.completedFuture(scoreJsonCache.get());
            }
        } catch (IOException e) {
            LOG.warn("Failed to get score from cache for score id {}: {}", id, e.getMessage());
        }

        if (ScoreId.isLocal(id)) {
            return CompletableFuture.completedFuture(null);
        }

        return executor.enqueueAsync(() -> OsuAPI.getScore(tokenManager.getTokenData(), id));
    }

    public void renderCustomTemplate(@NotNull Context context) {
        final String s = context.pathParam("templateName");

        final JsonObject data = JsonParser.parseString(context.body()).getAsJsonObject();

        final HashMap<String, CompletableFuture<Object>> variablesFuture = new HashMap<>();

        final JsonElement score = data.remove("@score");
        if (score != null) {
            final long scoreId = ScoreId.parse(score.getAsString());
            variablesFuture.put(
                    "score",
                    getScore(scoreId).thenApply(value -> value)
            );
        }

        final JsonElement user = data.remove("@user");
        if (user != null) {
            final long userId = user.getAsLong();
            variablesFuture.put(
                    "user",
                    executor.enqueueAsync(() -> OsuAPI.getUser(tokenManager.getTokenData(), userId))
            );
        }

        final JsonElement beatmap = data.remove("@beatmap");
        if (beatmap != null) {
            final long beatmapId = beatmap.getAsLong();
            variablesFuture.put(
                    "beatmap",
                    executor.enqueueAsync(() -> OsuAPI.getBeatmap(tokenManager.getTokenData(), beatmapId))
            );
        }

        final JsonElement beatmapset = data.remove("@beatmapset");
        if (beatmapset != null) {
            final long beatmapsetId = beatmapset.getAsLong();
            variablesFuture.put(
                    "beatmapset",
                    executor.enqueueAsync(() -> OsuAPI.getBeatmapset(tokenManager.getTokenData(), beatmapsetId))
            );
        }

        data.entrySet().forEach(entry -> {
            final String key = entry.getKey();
            final JsonElement value = entry.getValue();
            final Object obj;
            if (value.isJsonArray()) {
                obj = value.getAsJsonArray();
            } else if (value.isJsonObject()) {
                obj = value.getAsJsonObject();
            } else {
                final JsonPrimitive prim = value.getAsJsonPrimitive();
                if (prim.isBoolean()) {
                    obj = prim.getAsBoolean();
                } else if (prim.isNumber()) {
                    obj = prim.getAsNumber();
                } else {
                    obj = prim.getAsString();
                }
            }
            variablesFuture.put(key, CompletableFuture.completedFuture(obj));
        });

        context.future(() -> CompletableFuture.allOf(variablesFuture.values().toArray(new CompletableFuture[0]))
                .thenApply(_ -> {
                    HashMap<String, Object> variables = new HashMap<>();
                    for (Map.Entry<String, CompletableFuture<Object>> entry : variablesFuture.entrySet()) {
                        try {
                            variables.put(entry.getKey(), entry.getValue().get());
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to get variable " + entry.getKey(), e);
                        }
                    }
                    return variables;
                })
                .thenApplyAsync(variables -> renderer.renderCustomTemplate(s, variables), renderer.getRenderExecutor())
                .thenAccept(bytes -> context.status(200).result(bytes))
        );
    }
}
