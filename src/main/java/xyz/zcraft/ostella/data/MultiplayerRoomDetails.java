package xyz.zcraft.ostella.data;

import com.google.gson.annotations.SerializedName;
import lombok.Data;
import xyz.zcraft.osu.model.BeatmapExtended;
import xyz.zcraft.osu.model.User;

import java.util.List;

@Data
public class MultiplayerRoomDetails {
    private long id;
    private String name;
    private String status;
    private boolean active;

    @SerializedName("ends_at")
    private String endsAt;

    private User host;

    @SerializedName("current_playlist_item")
    private PlaylistItem currentPlaylistItem;

    private List<PlaylistItem> playlist;

    @SerializedName("recent_participants")
    private List<User> recentParticipants;

    @Data
    public static class PlaylistItem {
        private long id;

        @SerializedName("room_id")
        private long roomId;

        @SerializedName("beatmap_id")
        private long beatmapId;

        @SerializedName("owner_id")
        private long ownerId;

        @SerializedName("played_at")
        private String playedAt;

        private boolean expired;
        private BeatmapExtended beatmap;
    }
}
