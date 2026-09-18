package xyz.zcraft.ostella.network.controller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.javalin.http.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.zcraft.ostella.data.*;
import xyz.zcraft.ostella.exception.ApiException;
import xyz.zcraft.ostella.network.ErrorCode;
import xyz.zcraft.ostella.network.OsuAPI;
import xyz.zcraft.ostella.network.Response;
import xyz.zcraft.ostella.network.Router;
import xyz.zcraft.ostella.service.AsyncService;
import xyz.zcraft.ostella.service.MultiplayerResultFactory;
import xyz.zcraft.ostella.service.RenderService;
import xyz.zcraft.ostella.util.TokenManager;
import xyz.zcraft.osu.model.*;

import java.util.*;

public class MultiplayerController {
    public static final Gson GSON = new Gson();
    private static final Logger LOG = LogManager.getLogger(MultiplayerController.class);
    public final RenderService renderer;
    public final AsyncService executor;
    public final TokenManager tokenManager;
    public final Router router;

    public MultiplayerController(Router router) {
        this.router = router;
        this.renderer = router.renderer;
        this.executor = router.executor;
        this.tokenManager = router.tokenManager;
    }

    private static String scoreTeam(JsonObject score) {
        if (score.has("team") && !score.get("team").isJsonNull()) {
            return score.get("team").getAsString();
        }
        if (score.has("match") && score.get("match").isJsonObject()) {
            JsonObject match = score.getAsJsonObject("match");
            if (match.has("team") && !match.get("team").isJsonNull()) {
                return match.get("team").getAsString();
            }
        }
        return null;
    }

    private static void normalizeStableMods(JsonObject score) {
        if (!score.has("mods") || !score.get("mods").isJsonArray()) {
            return;
        }
        JsonArray mods = score.getAsJsonArray("mods");
        if (mods.isEmpty() || mods.get(0).isJsonObject()) {
            return;
        }
        JsonArray normalized = new JsonArray();
        for (JsonElement mod : mods) {
            JsonObject value = new JsonObject();
            value.addProperty("acronym", mod.getAsString());
            normalized.add(value);
        }
        score.add("mods", normalized);
    }

    private static Comparator<Score> stableScoreComparator(String scoringType) {
        return switch (scoringType == null ? "score" : scoringType.toLowerCase(Locale.ROOT)) {
            case "accuracy" -> Comparator.comparing(
                    Score::getAccuracy, Comparator.nullsLast(Comparator.reverseOrder())
            );
            case "combo" -> Comparator.comparing(
                    Score::getMaxCombo, Comparator.nullsLast(Comparator.reverseOrder())
            );
            default -> Comparator.comparing(
                    Score::getTotalScore, Comparator.nullsLast(Comparator.reverseOrder())
            );
        };
    }

    private static List<MultiplayerRoomDetails.PlaylistItem> completedItemsThrough(
            MultiplayerRoomDetails room,
            MultiplayerRoomDetails.PlaylistItem currentItem
    ) {
        Map<Long, MultiplayerRoomDetails.PlaylistItem> items = new LinkedHashMap<>();
        if (room.getPlaylist() != null) {
            room.getPlaylist().stream().filter(Objects::nonNull)
                    .forEach(item -> items.putIfAbsent(item.getId(), item));
        }
        items.putIfAbsent(currentItem.getId(), currentItem);

        List<MultiplayerRoomDetails.PlaylistItem> completed = new ArrayList<>();
        for (MultiplayerRoomDetails.PlaylistItem item : items.values()) {
            if (item.getId() == currentItem.getId()
                    || item.getPlayedAt() != null && !item.getPlayedAt().isBlank()) {
                completed.add(item);
            }
            if (item.getId() == currentItem.getId()) break;
        }
        return List.copyOf(completed);
    }

    private static String lazerTeamWinner(
            List<MultiplayerRoomScore> scores,
            MultiplayerRoomDetails.PlaylistItem item,
            MultiplayerRoomDetails.PlaylistItem eventItem
    ) {
        long red = 0;
        long blue = 0;
        boolean hasRed = false;
        boolean hasBlue = false;
        for (MultiplayerRoomScore roomScore : scores) {
            Score score = roomScore.score();
            if (score == null || score.getTotalScore() == null) continue;
            Long userId = scoreUserId(score);
            String team = normalizedTeam(firstNonBlank(
                    roomScore.team(),
                    item.teamFor(userId),
                    eventItem == null ? null : eventItem.teamFor(userId)
            ));
            if ("red".equals(team)) {
                red += score.getTotalScore();
                hasRed = true;
            } else if ("blue".equals(team)) {
                blue += score.getTotalScore();
                hasBlue = true;
            }
        }
        if (!hasRed || !hasBlue || red == blue) return null;
        return red > blue ? "red" : "blue";
    }

    private static Long lazerDuelWinner(List<MultiplayerRoomScore> scores, Set<Long> duelUsers) {
        Map<Long, Long> values = new HashMap<>();
        for (MultiplayerRoomScore roomScore : scores) {
            Score score = roomScore.score();
            Long userId = scoreUserId(score);
            if (userId != null && score.getTotalScore() != null) {
                values.put(userId, score.getTotalScore());
            }
        }
        if (!values.keySet().equals(duelUsers)) return null;
        List<Map.Entry<Long, Long>> ordered = values.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .toList();
        return ordered.get(0).getValue().equals(ordered.get(1).getValue())
                ? null : ordered.get(0).getKey();
    }

    private static MultiplayerResultData.SeriesScore stableSeriesScore(
            MultiplayerMatchDetails match,
            MultiplayerMatchDetails.MatchGame currentGame
    ) {
        boolean teamMode = isTeamMode(currentGame.getTeamType());
        Set<Long> duelUsers = teamMode ? Set.of() : stableUserIds(currentGame.getScores());
        if (!teamMode && duelUsers.size() != 2) {
            return MultiplayerResultData.SeriesScore.empty();
        }

        Map<Long, Integer> playerWins = new HashMap<>();
        int redWins = 0;
        int blueWins = 0;
        for (MultiplayerMatchDetails.MatchGame game : completedGamesThrough(match, currentGame)) {
            if (teamMode) {
                if (!isTeamMode(game.getTeamType())) continue;
                String winner = stableTeamWinner(game);
                if ("red".equals(winner)) redWins++;
                if ("blue".equals(winner)) blueWins++;
            } else {
                if (isTeamMode(game.getTeamType())) continue;
                Long winner = stableDuelWinner(game, duelUsers);
                if (winner != null) playerWins.merge(winner, 1, Integer::sum);
            }
        }
        return new MultiplayerResultData.SeriesScore(playerWins, redWins, blueWins);
    }

    private static List<MultiplayerMatchDetails.MatchGame> completedGamesThrough(
            MultiplayerMatchDetails match,
            MultiplayerMatchDetails.MatchGame currentGame
    ) {
        Map<Long, MultiplayerMatchDetails.MatchGame> games = new LinkedHashMap<>();
        if (match.getEvents() != null) {
            match.getEvents().stream()
                    .filter(Objects::nonNull)
                    .map(MultiplayerMatchDetails.MatchEvent::getGame)
                    .filter(Objects::nonNull)
                    .forEach(game -> games.putIfAbsent(game.getId(), game));
        }
        games.putIfAbsent(currentGame.getId(), currentGame);

        List<MultiplayerMatchDetails.MatchGame> completed = new ArrayList<>();
        for (MultiplayerMatchDetails.MatchGame game : games.values()) {
            if (game.getId() == currentGame.getId()
                    || game.getEndTime() != null && !game.getEndTime().isBlank()) {
                completed.add(game);
            }
            if (game.getId() == currentGame.getId()) break;
        }
        return List.copyOf(completed);
    }

    private static String stableTeamWinner(MultiplayerMatchDetails.MatchGame game) {
        double red = 0;
        double blue = 0;
        boolean hasRed = false;
        boolean hasBlue = false;
        for (JsonObject score : nullSafeScores(game.getScores())) {
            Double value = stableScoringValue(score, game.getScoringType());
            String team = normalizedTeam(scoreTeam(score));
            if (value == null) continue;
            if ("red".equals(team)) {
                red += value;
                hasRed = true;
            } else if ("blue".equals(team)) {
                blue += value;
                hasBlue = true;
            }
        }
        if (!hasRed || !hasBlue || Double.compare(red, blue) == 0) return null;
        return red > blue ? "red" : "blue";
    }

    private static Long stableDuelWinner(
            MultiplayerMatchDetails.MatchGame game,
            Set<Long> duelUsers
    ) {
        Map<Long, Double> values = new HashMap<>();
        for (JsonObject score : nullSafeScores(game.getScores())) {
            Long userId = stableUserId(score);
            Double value = stableScoringValue(score, game.getScoringType());
            if (userId != null && value != null) values.put(userId, value);
        }
        if (!values.keySet().equals(duelUsers)) return null;
        List<Map.Entry<Long, Double>> ordered = values.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .toList();
        return Double.compare(ordered.get(0).getValue(), ordered.get(1).getValue()) == 0
                ? null : ordered.get(0).getKey();
    }

    private static Double stableScoringValue(JsonObject score, String scoringType) {
        String normalized = scoringType == null ? "score" : scoringType.toLowerCase(Locale.ROOT);
        if ("accuracy".equals(normalized)) return jsonNumber(score, "accuracy");
        if ("combo".equals(normalized)) return jsonNumber(score, "max_combo");
        return jsonNumber(score, "total_score", "legacy_total_score", "classic_total_score", "score");
    }

    private static Double jsonNumber(JsonObject object, String... names) {
        for (String name : names) {
            if (object.has(name) && !object.get(name).isJsonNull()) {
                return object.get(name).getAsDouble();
            }
        }
        return null;
    }

    private static Set<Long> scoreUserIds(List<MultiplayerRoomScore> scores) {
        Set<Long> ids = new LinkedHashSet<>();
        for (MultiplayerRoomScore roomScore : scores) {
            Score score = roomScore.score();
            Long userId = scoreUserId(score);
            if (userId != null) ids.add(userId);
        }
        return Set.copyOf(ids);
    }

    private static Long scoreUserId(Score score) {
        if (score == null) return null;
        if (score.getUserId() != null && score.getUserId() > 0) return score.getUserId();
        return score.getUser() == null || score.getUser().getId() <= 0 ? null : score.getUser().getId();
    }

    private static Set<Long> stableUserIds(List<JsonObject> scores) {
        Set<Long> ids = new LinkedHashSet<>();
        for (JsonObject score : nullSafeScores(scores)) {
            Long userId = stableUserId(score);
            if (userId != null) ids.add(userId);
        }
        return Set.copyOf(ids);
    }

    private static Long stableUserId(JsonObject score) {
        if (score.has("user_id") && !score.get("user_id").isJsonNull()) {
            return score.get("user_id").getAsLong();
        }
        if (score.has("user") && score.get("user").isJsonObject()) {
            JsonObject user = score.getAsJsonObject("user");
            if (user.has("id") && !user.get("id").isJsonNull()) return user.get("id").getAsLong();
        }
        return null;
    }

    private static List<JsonObject> nullSafeScores(List<JsonObject> scores) {
        return scores == null ? List.of() : scores.stream().filter(Objects::nonNull).toList();
    }

    private static boolean isTeamMode(String teamType) {
        if (teamType == null) return false;
        String normalized = teamType.toLowerCase(Locale.ROOT).replace('_', '-');
        return normalized.contains("team") && !normalized.contains("head");
    }

    private static String normalizedTeam(String team) {
        if (team == null) return null;
        return switch (team.toLowerCase(Locale.ROOT)) {
            case "red", "1" -> "red";
            case "blue", "2" -> "blue";
            default -> null;
        };
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    static MultiplayerRoomWatchState toWatchState(MultiplayerRoomDetails room) {
        Map<Long, MultiplayerRoomDetails.PlaylistItem> items = new LinkedHashMap<>();
        if (room.getPlaylist() != null) {
            room.getPlaylist().stream()
                    .filter(Objects::nonNull)
                    .forEach(item -> items.put(item.getId(), item));
        }
        if (room.getCurrentPlaylistItem() != null) {
            items.putIfAbsent(room.getCurrentPlaylistItem().getId(), room.getCurrentPlaylistItem());
        }

        List<MultiplayerRoomWatchState.CompletedPlay> completed = items.values().stream()
                .filter(item -> item.getId() > 0 && item.getPlayedAt() != null && !item.getPlayedAt().isBlank())
                .sorted(Comparator
                        .comparing(MultiplayerRoomDetails.PlaylistItem::getPlayedAt)
                        .thenComparingLong(MultiplayerRoomDetails.PlaylistItem::getId))
                .map(item -> new MultiplayerRoomWatchState.CompletedPlay(item.getId(), item.getPlayedAt()))
                .toList();
        boolean active = room.isActive()
                && (room.getStatus() == null || !room.getStatus().equalsIgnoreCase("ended"));
        return new MultiplayerRoomWatchState(room.getId(), room.getName(), active, completed);
    }

    static MultiplayerRoomWatchState toWatchState(MultiplayerMatchDetails match) {
        Map<Long, MultiplayerMatchDetails.MatchGame> games = new LinkedHashMap<>();
        if (match.getEvents() != null) {
            match.getEvents().stream()
                    .filter(Objects::nonNull)
                    .map(MultiplayerMatchDetails.MatchEvent::getGame)
                    .filter(Objects::nonNull)
                    .forEach(game -> games.put(game.getId(), game));
        }
        List<MultiplayerRoomWatchState.CompletedPlay> completed = games.values().stream()
                .filter(game -> game.getId() > 0 && game.getEndTime() != null && !game.getEndTime().isBlank())
                .sorted(Comparator
                        .comparing(MultiplayerMatchDetails.MatchGame::getEndTime)
                        .thenComparingLong(MultiplayerMatchDetails.MatchGame::getId))
                .map(game -> new MultiplayerRoomWatchState.CompletedPlay(game.getId(), game.getEndTime()))
                .toList();
        MultiplayerMatchDetails.MatchInfo info = match.getMatch();
        boolean active = info.getEndTime() == null || info.getEndTime().isBlank();
        return new MultiplayerRoomWatchState(info.getId(), info.getName(), active, completed);
    }

    private static MultiplayerMatchDetails.MatchGame findMatchGame(
            MultiplayerMatchDetails match,
            long gameId
    ) {
        if (match.getEvents() != null) {
            MultiplayerMatchDetails.MatchGame game = match.getEvents().stream()
                    .filter(Objects::nonNull)
                    .map(MultiplayerMatchDetails.MatchEvent::getGame)
                    .filter(Objects::nonNull)
                    .filter(value -> value.getId() == gameId)
                    .findFirst()
                    .orElse(null);
            if (game != null) {
                return game;
            }
        }
        throw new ApiException(ErrorCode.NO_BEATMAP_FOUND, "Game was not found in match");
    }

    private static MultiplayerRoomDetails.PlaylistItem findPlaylistItem(
            MultiplayerRoomDetails room,
            long playlistItemId
    ) {
        if (room.getPlaylist() != null) {
            MultiplayerRoomDetails.PlaylistItem item = room.getPlaylist().stream()
                    .filter(Objects::nonNull)
                    .filter(value -> value.getId() == playlistItemId)
                    .findFirst()
                    .orElse(null);
            if (item != null) {
                return item;
            }
        }
        if (room.getCurrentPlaylistItem() != null
                && room.getCurrentPlaylistItem().getId() == playlistItemId) {
            return room.getCurrentPlaylistItem();
        }
        throw new ApiException(ErrorCode.NO_BEATMAP_FOUND, "Playlist item was not found in room");
    }

    private static long positivePathId(Context context, String name) {
        String value = context.pathParam(name);
        try {
            long id = Long.parseLong(value);
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
        }
        throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, name + " must be a positive integer");
    }

    private static RoomVersion roomVersion(Context context) {
        String value = context.queryParam("version");
        if (value == null || value.isBlank() || value.equalsIgnoreCase("lazer")) {
            return RoomVersion.LAZER;
        }
        if (value.equalsIgnoreCase("stable")) {
            return RoomVersion.STABLE;
        }
        throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, "version must be stable or lazer");
    }

    public void getCurrentRoom(@NotNull Context context) {
        final String auth = context.header("Authorization");

        if (auth == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }

        context.future(() -> executor
                .enqueueAsync(() -> OsuAPI.getCurrentRoom(auth))
                .thenApply(room -> {
                    if (room == null) {
                        throw new ApiException(ErrorCode.NO_ROOM_FOUND, "User is not in a room!");
                    }
                    return room;
                })
                .thenAccept(room -> context.status(200).result(new Response(true, "Success", GSON.toJsonTree(room)).toString()))
        );
    }

    public void getCurrentRoomItem(@NotNull Context context) {
        final String auth = context.header("Authorization");

        if (auth == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }

        context.future(() -> executor
                .enqueueAsync(() -> OsuAPI.getCurrentRoom(auth))
                .thenApply(room -> {
                    if (room == null) {
                        throw new ApiException(ErrorCode.NO_ROOM_FOUND, "User is not in a room!");
                    }
                    final var currentPlaylistItem = room.getCurrentPlaylistItem();
                    if (currentPlaylistItem == null) {
                        throw new ApiException(ErrorCode.NO_BEATMAPSET_FOUND, "Room has no current playlist item!");
                    }
                    return currentPlaylistItem;
                })
                .thenApply((MultiplayerRoom.CurrentPlaylistItem c) -> {
                    final BeatmapExtended beatmap = c.getBeatmap();
                    if (beatmap == null) {
                        throw new ApiException(ErrorCode.NO_BEATMAP_FOUND, "Beatmap is null!");
                    }
                    JsonObject res = new JsonObject();
                    res.addProperty("beatmap_id", beatmap.getId());
                    res.addProperty("beatmapset_id", beatmap.getBeatmapsetId());
                    return res;
                })
                .thenAccept(obj -> context.status(200).result(new Response(true, "Success", obj).toString()))
        );
    }

    public void getRoomWatchState(@NotNull Context context) {
        long roomId = positivePathId(context, "roomId");
        RoomVersion version = roomVersion(context);
        context.future(() -> executor
                .enqueueAsync(() -> switch (version) {
                    case LAZER -> toWatchState(OsuAPI.getRoom(tokenManager.getTokenData(), roomId));
                    case STABLE -> toWatchState(OsuAPI.getMatch(tokenManager.getTokenData(), roomId));
                })
                .thenAccept(state -> context.status(200)
                        .contentType("application/json")
                        .result(new Response(true, "Success", GSON.toJsonTree(state)).toString()))
        );
    }

    public void renderRoomResult(@NotNull Context context) {
        long roomId = positivePathId(context, "roomId");
        long playlistItemId = positivePathId(context, "playlistItemId");
        RoomVersion version = roomVersion(context);
        context.future(() -> executor
                .enqueueAsync(() -> switch (version) {
                    case LAZER -> getLazerResultData(roomId, playlistItemId);
                    case STABLE -> getStableResultData(roomId, playlistItemId);
                })
                .thenApplyAsync(renderer::renderMultiplayerResult, renderer.getRenderExecutor())
                .thenAccept(bytes -> context.status(200).contentType("image/png").result(bytes))
        );
    }

    private MultiplayerResultData getLazerResultData(long roomId, long playlistItemId) {
        MultiplayerRoomDetails room = OsuAPI.getRoom(tokenManager.getTokenData(), roomId);
        MultiplayerRoomDetails.PlaylistItem item = findPlaylistItem(room, playlistItemId);
        enrichPlaylistItem(item);

        List<MultiplayerRoomScore> roomScores = OsuAPI.getRoomPlaylistScores(
                tokenManager.getTokenData(), roomId, playlistItemId
        );
        List<MultiplayerRoomDetails.PlaylistItem> eventItems = isTeamMode(room.getType())
                ? OsuAPI.getRoomEventPlaylistItems(tokenManager.getTokenData(), roomId)
                : List.of();
        enrichLazerTeamSnapshot(room, item, roomScores, eventItems);
        MultiplayerResultData.SeriesScore seriesScore = lazerSeriesScore(
                room, item, roomScores, eventItems);
        enrichScores(roomScores, item);
        enrichDuelProfiles(roomScores);
        User owner = resolveOwner(room, item.getOwnerId());
        return MultiplayerResultFactory.create(
                room,
                item,
                roomScores,
                owner,
                "lazer",
                "scorev2",
                room.getType(),
                seriesScore
        );
    }

    private void enrichLazerTeamSnapshot(
            MultiplayerRoomDetails room,
            MultiplayerRoomDetails.PlaylistItem item,
            List<MultiplayerRoomScore> roomScores,
            List<MultiplayerRoomDetails.PlaylistItem> eventItems
    ) {
        String roomType = room.getType();
        boolean teamVs = roomType != null
                && roomType.toLowerCase(Locale.ROOT).replace('-', '_').contains("team");
        boolean missingTeam = roomScores.stream().anyMatch(roomScore ->
                roomScore.team() == null || roomScore.team().isBlank());
        if (!teamVs || !missingTeam) {
            return;
        }

        MultiplayerRoomDetails.PlaylistItem eventItem = eventItems.stream()
                .filter(value -> value.getId() == item.getId())
                .findFirst()
                .orElse(null);
        if (eventItem == null || eventItem.getDetails() == null) {
            LOG.warn("Room events contain no details for playlist item {} in room {}", item.getId(), room.getId());
            return;
        }
        item.setDetails(eventItem.getDetails());
    }

    private MultiplayerResultData getStableResultData(long matchId, long gameId) {
        MultiplayerMatchDetails match = OsuAPI.getMatch(tokenManager.getTokenData(), matchId);
        MultiplayerMatchDetails.MatchGame game = findMatchGame(match, gameId);

        MultiplayerRoomDetails room = new MultiplayerRoomDetails();
        room.setId(match.getMatch().getId());
        room.setName(match.getMatch().getName());
        room.setActive(match.getMatch().getEndTime() == null || match.getMatch().getEndTime().isBlank());
        room.setRecentParticipants(match.getUsers());

        MultiplayerRoomDetails.PlaylistItem item = new MultiplayerRoomDetails.PlaylistItem();
        item.setId(game.getId());
        item.setRoomId(matchId);
        item.setBeatmapId(game.getBeatmapId());
        item.setPlayedAt(game.getEndTime());
        item.setBeatmap(game.getBeatmap());
        enrichPlaylistItem(item);

        List<MultiplayerRoomScore> scores = stableScores(match, game, item);
        MultiplayerResultData.SeriesScore seriesScore = stableSeriesScore(match, game);
        enrichDuelProfiles(scores);
        User stableLobby = new User();
        stableLobby.setUsername("Stable lobby");
        return MultiplayerResultFactory.create(
                room,
                item,
                scores,
                stableLobby,
                "stable",
                game.getScoringType(),
                game.getTeamType(),
                seriesScore
        );
    }

    private List<MultiplayerRoomScore> stableScores(
            MultiplayerMatchDetails match,
            MultiplayerMatchDetails.MatchGame game,
            MultiplayerRoomDetails.PlaylistItem item
    ) {
        Map<Long, User> users = new HashMap<>();
        if (match.getUsers() != null) {
            match.getUsers().stream().filter(Objects::nonNull).forEach(user -> users.put(user.getId(), user));
        }

        Comparator<Score> scoreComparator = stableScoreComparator(game.getScoringType());
        List<MultiplayerRoomScore> scores = (game.getScores() == null
                ? List.<JsonObject>of()
                : game.getScores()).stream()
                .map(value -> new MultiplayerRoomScore(
                        stableScore(value, game, item, users),
                        null,
                        scoreTeam(value)
                ))
                .sorted((left, right) -> scoreComparator.compare(left.score(), right.score()))
                .toList();

        List<MultiplayerRoomScore> result = new java.util.ArrayList<>(scores.size());
        for (int index = 0; index < scores.size(); index++) {
            MultiplayerRoomScore roomScore = scores.get(index);
            result.add(new MultiplayerRoomScore(roomScore.score(), index + 1, roomScore.team()));
        }
        return List.copyOf(result);
    }

    private Score stableScore(
            JsonObject value,
            MultiplayerMatchDetails.MatchGame game,
            MultiplayerRoomDetails.PlaylistItem item,
            Map<Long, User> users
    ) {
        JsonObject normalized = value.deepCopy();
        normalizeStableMods(normalized);
        if (!normalized.has("total_score")) {
            if (normalized.has("legacy_total_score")) {
                normalized.add("total_score", normalized.get("legacy_total_score"));
            } else if (normalized.has("classic_total_score")) {
                normalized.add("total_score", normalized.get("classic_total_score"));
            } else if (normalized.has("score")) {
                normalized.add("total_score", normalized.get("score"));
            }
        }
        if (!normalized.has("beatmap_id")) {
            normalized.addProperty("beatmap_id", game.getBeatmapId());
        }
        if (!normalized.has("ended_at") && game.getEndTime() != null) {
            normalized.addProperty("ended_at", game.getEndTime());
        }
        if (!normalized.has("passed") && normalized.has("match") && normalized.get("match").isJsonObject()) {
            JsonObject scoreMatch = normalized.getAsJsonObject("match");
            if (scoreMatch.has("pass")) {
                normalized.add("passed", scoreMatch.get("pass"));
            }
        }

        Score score = GSON.fromJson(normalized, Score.class);
        score.setBeatmap(item.getBeatmap());
        if (item.getBeatmap() != null) {
            score.setBeatmapset(item.getBeatmap().getBeatmapset());
        }
        if (score.getUserId() != null && score.getUserId() > 0) {
            User user = users.computeIfAbsent(
                    score.getUserId(),
                    id -> OsuAPI.getUser(tokenManager.getTokenData(), id)
            );
            score.setUser(user);
        }
        if (score.getPassed() == null) {
            score.setPassed(true);
        }
        if (score.getRank() == null || score.getRank().isBlank()) {
            score.setRank(Boolean.TRUE.equals(score.getPassed()) ? "-" : "F");
        }
        if (score.getBeatmap() != null) {
            try {
                router.ensurePp(score);
            } catch (RuntimeException e) {
                LOG.warn("Failed to estimate pp for stable multiplayer score {}", score.getId(), e);
            }
        }
        return score;
    }

    private MultiplayerResultData.SeriesScore lazerSeriesScore(
            MultiplayerRoomDetails room,
            MultiplayerRoomDetails.PlaylistItem currentItem,
            List<MultiplayerRoomScore> currentScores,
            List<MultiplayerRoomDetails.PlaylistItem> eventItems
    ) {
        boolean teamMode = isTeamMode(room.getType());
        Set<Long> duelUsers = teamMode ? Set.of() : scoreUserIds(currentScores);
        if (!teamMode && duelUsers.size() != 2) {
            return MultiplayerResultData.SeriesScore.empty();
        }

        Map<Long, MultiplayerRoomDetails.PlaylistItem> eventsById = new HashMap<>();
        eventItems.forEach(item -> eventsById.put(item.getId(), item));
        Map<Long, Integer> playerWins = new HashMap<>();
        int redWins = 0;
        int blueWins = 0;

        for (MultiplayerRoomDetails.PlaylistItem item : completedItemsThrough(room, currentItem)) {
            List<MultiplayerRoomScore> scores;
            if (item.getId() == currentItem.getId()) {
                scores = currentScores;
            } else {
                try {
                    scores = OsuAPI.getRoomPlaylistScores(
                            tokenManager.getTokenData(), room.getId(), item.getId());
                } catch (ApiException e) {
                    LOG.warn("Failed to include playlist item {} in room {} series score",
                            item.getId(), room.getId(), e);
                    continue;
                }
            }

            if (teamMode) {
                String winner = lazerTeamWinner(scores, item, eventsById.get(item.getId()));
                if ("red".equals(winner)) redWins++;
                if ("blue".equals(winner)) blueWins++;
            } else {
                Long winner = lazerDuelWinner(scores, duelUsers);
                if (winner != null) playerWins.merge(winner, 1, Integer::sum);
            }
        }
        return new MultiplayerResultData.SeriesScore(playerWins, redWins, blueWins);
    }

    private void enrichPlaylistItem(MultiplayerRoomDetails.PlaylistItem item) {
        if (item.getBeatmap() == null || item.getBeatmap().getBeatmapset() == null) {
            BeatmapExtended beatmap = OsuAPI.getBeatmap(tokenManager.getTokenData(), item.getBeatmapId());
            if (beatmap != null) {
                item.setBeatmap(beatmap);
            }
        }
    }

    private void enrichScores(
            List<MultiplayerRoomScore> roomScores,
            MultiplayerRoomDetails.PlaylistItem item
    ) {
        Map<Long, BeatmapExtended> beatmaps = new HashMap<>();
        if (item.getBeatmap() != null) {
            beatmaps.put(item.getBeatmapId(), item.getBeatmap());
        }
        Map<Long, User> users = new HashMap<>();

        for (MultiplayerRoomScore roomScore : roomScores) {
            Score score = roomScore.score();
            if (score == null) {
                continue;
            }
            long beatmapId = score.getBeatmapId() == null
                    ? item.getBeatmapId()
                    : score.getBeatmapId();
            BeatmapExtended scoreBeatmap = score.getBeatmap();
            if (scoreBeatmap == null || scoreBeatmap.getBeatmapset() == null) {
                scoreBeatmap = beatmaps.computeIfAbsent(
                        beatmapId,
                        id -> OsuAPI.getBeatmap(tokenManager.getTokenData(), id)
                );
                if (scoreBeatmap != null) {
                    score.setBeatmap(scoreBeatmap);
                    score.setBeatmapset(scoreBeatmap.getBeatmapset());
                }
            }

            if (score.getUser() == null && score.getUserId() != null && score.getUserId() > 0) {
                User user = users.computeIfAbsent(
                        score.getUserId(),
                        id -> OsuAPI.getUser(tokenManager.getTokenData(), id)
                );
                score.setUser(user);
            } else if (score.getUser() != null) {
                users.put(score.getUser().getId(), score.getUser());
            }
            if (score.getBeatmap() != null) {
                try {
                    router.ensurePp(score);
                } catch (RuntimeException e) {
                    LOG.warn("Failed to estimate pp for multiplayer score {}", score.getId(), e);
                }
            }
        }
    }

    private User resolveOwner(MultiplayerRoomDetails room, long ownerId) {
        if (room.getRecentParticipants() != null) {
            User participant = room.getRecentParticipants().stream()
                    .filter(Objects::nonNull)
                    .filter(user -> user.getId() == ownerId)
                    .findFirst()
                    .orElse(null);
            if (participant != null) {
                return participant;
            }
        }
        if (room.getHost() != null && room.getHost().getId() == ownerId) {
            return room.getHost();
        }
        return ownerId > 0 ? OsuAPI.getUser(tokenManager.getTokenData(), ownerId) : room.getHost();
    }

    private void enrichDuelProfiles(List<MultiplayerRoomScore> roomScores) {
        if (roomScores.size() != 2) {
            return;
        }
        for (MultiplayerRoomScore roomScore : roomScores) {
            Score score = roomScore.score();
            if (score == null || score.getUser() instanceof UserExtended) {
                continue;
            }
            long userId = score.getUserId() == null
                    ? score.getUser() == null ? 0 : score.getUser().getId()
                    : score.getUserId();
            if (userId <= 0) {
                continue;
            }
            UserExtended user = OsuAPI.getUser(tokenManager.getTokenData(), userId);
            if (user != null) {
                score.setUser(user);
            }
        }
    }

    private enum RoomVersion {
        LAZER,
        STABLE
    }
}
