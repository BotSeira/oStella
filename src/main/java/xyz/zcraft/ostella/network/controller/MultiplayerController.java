package xyz.zcraft.ostella.network.controller;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.javalin.http.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.zcraft.ostella.exception.ApiException;
import xyz.zcraft.ostella.data.MultiplayerResultData;
import xyz.zcraft.ostella.data.MultiplayerRoomDetails;
import xyz.zcraft.ostella.data.MultiplayerRoomScore;
import xyz.zcraft.ostella.data.MultiplayerRoomWatchState;
import xyz.zcraft.ostella.network.*;
import xyz.zcraft.ostella.service.AsyncService;
import xyz.zcraft.ostella.service.MultiplayerResultFactory;
import xyz.zcraft.ostella.service.RenderService;
import xyz.zcraft.ostella.util.TokenManager;
import xyz.zcraft.osu.model.BeatmapExtended;
import xyz.zcraft.osu.model.MultiplayerRoom;
import xyz.zcraft.osu.model.Score;
import xyz.zcraft.osu.model.User;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MultiplayerController {
    private static final Logger LOG = LogManager.getLogger(MultiplayerController.class);
    public static final Gson GSON = new Gson();

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
        context.future(() -> executor
                .enqueueAsync(() -> OsuAPI.getRoom(tokenManager.getTokenData(), roomId))
                .thenApply(MultiplayerController::toWatchState)
                .thenAccept(state -> context.status(200)
                        .contentType("application/json")
                        .result(new Response(true, "Success", GSON.toJsonTree(state)).toString()))
        );
    }

    public void renderRoomResult(@NotNull Context context) {
        long roomId = positivePathId(context, "roomId");
        long playlistItemId = positivePathId(context, "playlistItemId");
        context.future(() -> executor
                .enqueueAsync(() -> getResultData(roomId, playlistItemId))
                .thenApplyAsync(renderer::renderMultiplayerResult, renderer.getRenderExecutor())
                .thenAccept(bytes -> context.status(200).contentType("image/png").result(bytes))
        );
    }

    private MultiplayerResultData getResultData(long roomId, long playlistItemId) {
        MultiplayerRoomDetails room = OsuAPI.getRoom(tokenManager.getTokenData(), roomId);
        MultiplayerRoomDetails.PlaylistItem item = findPlaylistItem(room, playlistItemId);
        enrichPlaylistItem(item);

        List<MultiplayerRoomScore> roomScores = OsuAPI.getRoomPlaylistScores(
                tokenManager.getTokenData(), roomId, playlistItemId
        );
        enrichScores(roomScores, item);
        User owner = resolveOwner(room, item.getOwnerId());
        return MultiplayerResultFactory.create(room, item, roomScores, owner);
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
}
