package xyz.zcraft.ostella.network.controller;

import com.google.gson.Gson;
import io.javalin.http.Context;
import org.jetbrains.annotations.NotNull;
import xyz.zcraft.ostella.data.ScoreRecord;
import xyz.zcraft.ostella.db.ScoreStore;
import xyz.zcraft.ostella.exception.ApiException;
import xyz.zcraft.ostella.network.ErrorCode;
import xyz.zcraft.ostella.network.ImageResponse;
import xyz.zcraft.ostella.network.OsuAPI;
import xyz.zcraft.ostella.network.Router;
import xyz.zcraft.ostella.service.AsyncService;
import xyz.zcraft.ostella.service.CacheService;
import xyz.zcraft.ostella.service.RenderService;
import xyz.zcraft.ostella.util.TokenManager;
import xyz.zcraft.osu.model.BeatmapExtended;
import xyz.zcraft.osu.model.Score;
import xyz.zcraft.osu.model.User;
import xyz.zcraft.osu.parser.BeatmapParser;
import xyz.zcraft.osu.parser.OsuParser;
import xyz.zcraft.osu.parser.data.beatmap.DiffSpec;
import xyz.zcraft.osu.parser.data.beatmap.OsuBeatmap;
import xyz.zcraft.osu.parser.exception.AnalyzeException;
import xyz.zcraft.osu.parser.exception.ParseException;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static xyz.zcraft.ostella.util.RequestUtil.optionalBoolean;
import static xyz.zcraft.ostella.util.RequestUtil.requirePathLong;

public class LeaderboardController {
    private static final Gson GSON = new Gson();

    public final RenderService renderer;
    public final AsyncService executor;
    public final TokenManager tokenManager;
    public final Router router;

    public LeaderboardController(Router router) {
        this.router = router;
        this.renderer = router.renderer;
        this.tokenManager = router.tokenManager;
        this.executor = router.executor;
    }

    public void getMapLeaderboard(@NotNull Context context) {
        final long m = requirePathLong(context, "beatmapId");
        final LeaderboardRequest leaderboardRequest = GSON.fromJson(context.body(), LeaderboardRequest.class);

        if (leaderboardRequest.uids() == null || leaderboardRequest.uids().isEmpty()) {
            throw new IllegalArgumentException("No user provided");
        }

        final boolean doUpdate = optionalBoolean(context, "update", false);

        context.header("X-Beatmap-Id", String.valueOf(m));

        if (doUpdate) {
            context.future(() -> getPlacementsAsync(leaderboardRequest.uids(), m)
                    .thenApply(p -> {
                        p.sort((a, b) -> Double.compare(b.getPp(), a.getPp()));
                        return p;
                    })
                    .thenCompose(scores ->
                            executor.enqueueAsync(() -> OsuAPI.getBeatmap(tokenManager.getTokenData(), m))
                                    .thenApply(beatmap -> {
                                        if (beatmap == null) throw new ApiException(ErrorCode.NO_BEATMAP_FOUND);
                                        ScoreStore.upsertScores(scores);
                                        return new MapLeaderboardData(beatmap, scores);
                                    }))
                    .thenCompose(data -> ImageResponse.respond(context, data,
                            this::renderMapLeaderboard, renderer.getRenderExecutor())));
        } else {
            context.future(() -> executor
                    .enqueueAsync(() -> OsuAPI.getBeatmap(tokenManager.getTokenData(), m))
                    .thenCompose(beatmap -> {
                        if (beatmap == null) throw new ApiException(ErrorCode.NO_BEATMAP_FOUND);

                        final List<Score> scoresRaw = ScoreStore.getScoresForUsers(m, leaderboardRequest.uids(), ScoreStore.SortType.PP)
                                .stream()
                                .map(ScoreRecord::toScore)
                                .peek(score -> score.setBeatmap(beatmap))
                                .peek(score -> score.setBeatmapset(beatmap.getBeatmapset()))
                                .toList();

                        final Map<Long, Score> userScores = new HashMap<>();
                        scoresRaw.forEach(score -> {
                            if (userScores.get(score.getUserId()) == null
                                    || userScores.get(score.getUserId()).getPp() < score.getPp()) {
                                userScores.put(score.getUserId(), score);
                            }
                        });

                        final List<Score> scores = userScores.values().stream().toList();

                        return executor.enqueueAsync(() -> OsuAPI.getUsers(tokenManager.getTokenData(), scores.stream().map(Score::getUserId).toList()))
                                .thenApply(users -> users.stream().collect(Collectors.toMap(User::getId, user -> user)))
                                .thenApply(usersMap -> scores
                                        .stream()
                                        .peek(s -> s.setUser(usersMap.get(s.getUserId())))
                                        .sorted(Comparator.comparing(Score::getPp).reversed())
                                        .toList())
                                .thenApply(_ -> new MapLeaderboardData(beatmap, scores));
                    })
                    .thenCompose(data -> ImageResponse.respond(context, data, this::renderMapLeaderboard, renderer.getRenderExecutor())));
        }
    }

    private byte[] renderMapLeaderboard(MapLeaderboardData data) {
        BeatmapExtended beatmap = data.beatmap();
        Path beatmapPath = CacheService.getBeatmapPath(beatmap.getId());
        try {
            final OsuBeatmap osuBeatmap = BeatmapParser.parseBeatmap(beatmapPath);
            final DiffSpec diffSpecForMap = OsuParser.getDiffSpecForMap(osuBeatmap, "");
            return renderer.renderMapLeaderboard(beatmap, data.scores(), diffSpecForMap.getPpSS());
        } catch (ParseException | AnalyzeException e) {
            throw new ApiException(ErrorCode.BEATMAP_PARSE_FAILED, "Failed to calculate difficulty", e);
        }
    }

    private CompletableFuture<LinkedList<Score>> getPlacementsAsync(List<Long> u, long id) {
        List<CompletableFuture<Score>> placementFutures = u.stream().map(
                s -> executor.enqueueAsync(() -> OsuAPI.getUserScore(tokenManager.getTokenData(), s, id))
        ).toList();

        CompletableFuture<?>[] futuresArray = placementFutures.toArray(new CompletableFuture[0]);

        return CompletableFuture.allOf(futuresArray)
                .thenApply(_ ->
                        placementFutures
                                .stream()
                                .map(CompletableFuture::join)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toCollection(LinkedList::new)));
    }

    public void getLeaderboard(@NotNull Context context) {
        final LeaderboardRequest leaderboardRequest = GSON.fromJson(context.body(), LeaderboardRequest.class);
        final List<Long> ids = leaderboardRequest.uids();

        List<CompletableFuture<List<User>>> futures = new ArrayList<>();

        for (int i = 0; i < ids.size(); i += 50) {
            final List<Long> subList = ids.subList(i, Math.min(i + 50, ids.size()));

            futures.add(executor.enqueueAsync(() ->
                    OsuAPI.getUsers(tokenManager.getTokenData(), subList)
            ));
        }

        CompletableFuture<?>[] futuresArray = futures.toArray(new CompletableFuture[0]);
        context.future(() ->
                CompletableFuture.allOf(futuresArray)
                        .thenApply(_ ->
                                futures.stream()
                                        .map(CompletableFuture::join)
                                        .filter(Objects::nonNull)
                                        .flatMap(List::stream)
                                        .collect(Collectors.toCollection(LinkedList::new)))
                        .thenApply(users -> {
                            users.sort(Comparator.comparingDouble((User user) ->
                                    Optional.ofNullable(user)
                                            .map(User::getStatisticsRulesets)
                                            .map(User.StatisticsRuleset::getOsu)
                                            .map(User.Statistics::getPp)
                                            .orElse(0.0)
                            ).reversed());
                            return users;

                        })
                        .thenCompose(users -> ImageResponse.respond(context, users,
                                renderer::renderLeaderboard, renderer.getRenderExecutor()))
        );
    }

    private record MapLeaderboardData(BeatmapExtended beatmap, List<Score> scores) {
    }

    public record LeaderboardRequest(List<Long> uids) {
    }
}
