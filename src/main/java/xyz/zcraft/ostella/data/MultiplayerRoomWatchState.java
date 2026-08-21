package xyz.zcraft.ostella.data;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public record MultiplayerRoomWatchState(
        @SerializedName("room_id") long roomId,
        @SerializedName("room_name") String roomName,
        boolean active,
        @SerializedName("completed_plays") List<CompletedPlay> completedPlays
) {
    public MultiplayerRoomWatchState {
        completedPlays = List.copyOf(completedPlays);
    }

    public record CompletedPlay(
            @SerializedName("playlist_item_id") long playlistItemId,
            @SerializedName("played_at") String playedAt
    ) {
    }
}
