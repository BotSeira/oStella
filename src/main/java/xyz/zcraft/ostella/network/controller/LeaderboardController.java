package xyz.zcraft.ostella.network.controller;

import com.google.gson.Gson;
import io.javalin.http.Context;
import org.jetbrains.annotations.NotNull;
import xyz.zcraft.ostella.data.Placement;
import xyz.zcraft.ostella.exception.ApiException;
import xyz.zcraft.ostella.network.ErrorCode;
import xyz.zcraft.ostella.network.OsuAPI;
import xyz.zcraft.ostella.network.Router;
import xyz.zcraft.ostella.service.AsyncService;
import xyz.zcraft.ostella.service.CacheService;
import xyz.zcraft.ostella.service.RenderService;
import xyz.zcraft.ostella.util.TokenManager;
import xyz.zcraft.osu.model.BeatmapExtended;
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

        context.header("X-Beatmap-Id", String.valueOf(m));

        context.future(() -> getPlacementsAsync(leaderboardRequest.uids(), m)
                .thenApply(p -> {
                    p.sort((a, b) -> Long.compare(b.getScore().getTotalScore(), a.getScore().getTotalScore()));
                    return p;
                })
                .thenCompose(placements ->
                        executor.enqueueAsync(() -> OsuAPI.getBeatmap(tokenManager.getTokenData(), m))
                                .thenApplyAsync(beatmap -> {
                                    if (beatmap == null) throw new ApiException(ErrorCode.NO_BEATMAP_FOUND);
                                    final Path rosuBeatmapPath = CacheService.getBeatmapPath(m);

                                    return finalizeMapLeaderboard(placements, beatmap, rosuBeatmapPath);
                                }, renderer.getRenderExecutor()))
                .thenAccept(imgByte -> context.status(200).result(imgByte)));
    }

    private byte[] finalizeMapLeaderboard(LinkedList<Placement> placements, BeatmapExtended beatmap, Path beatmapPath) {
        try {
            final OsuBeatmap osuBeatmap = BeatmapParser.parseBeatmap(beatmapPath);
            final DiffSpec diffSpecForMap = OsuParser.getDiffSpecForMap(osuBeatmap, "");
            return renderer.renderMapLeaderboard(beatmap, placements, diffSpecForMap.getPpSS());
        } catch (ParseException | AnalyzeException e) {
            throw new ApiException(ErrorCode.BEATMAP_PARSE_FAILED, "Failed to calculate difficulty", e);
        }
    }

    private CompletableFuture<LinkedList<Placement>> getPlacementsAsync(List<Long> u, long id) {
        List<CompletableFuture<Placement>> placementFutures = u.stream().map(s ->
                executor
                        .enqueueAsync(() ->
                                OsuAPI.getUserScore(tokenManager.getTokenData(), s, id)
                        )
                        .thenCompose(score -> {
                            if (score == null) {
                                return CompletableFuture.completedFuture(null);
                            }

                            router.ensurePp(score);

                            return executor
                                    .enqueueAsync(() ->
                                            OsuAPI.getUser(tokenManager.getTokenData(), s)
                                    )
                                    .thenApply(user -> {
                                        if (user == null) return null;

                                        final Placement placement = new Placement();
                                        placement.setScore(score);
                                        placement.setUser(user);
                                        return placement;
                                    });
                        })).toList();

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
                        .thenApplyAsync(users -> {
                            users.sort(Comparator.comparingDouble((User user) ->
                                    Optional.ofNullable(user)
                                            .map(User::getStatisticsRulesets)
                                            .map(User.StatisticsRuleset::getOsu)
                                            .map(User.Statistics::getPp)
                                            .orElse(0.0)
                            ).reversed());
                            return renderer.renderLeaderboard(users);

                        }, renderer.getRenderExecutor())
                        .thenAccept(imgByte -> context.status(200).result(imgByte))
        );
    }

    public record LeaderboardRequest(List<Long> uids) {
    }
}
