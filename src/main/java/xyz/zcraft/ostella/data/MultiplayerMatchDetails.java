package xyz.zcraft.ostella.data;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import xyz.zcraft.osu.model.BeatmapExtended;
import xyz.zcraft.osu.model.User;

import java.util.List;

@Data
public class MultiplayerMatchDetails {
    private MatchInfo match;
    private List<MatchEvent> events;
    private List<User> users;

    @SerializedName("latest_event_id")
    private Long latestEventId;

    @SerializedName("current_game_id")
    private Long currentGameId;

    @Data
    public static class MatchInfo {
        private long id;
        private String name;

        @SerializedName("start_time")
        private String startTime;

        @SerializedName("end_time")
        private String endTime;
    }

    @Data
    public static class MatchEvent {
        private long id;
        private String timestamp;
        private MatchGame game;
    }

    @Data
    public static class MatchGame {
        private long id;

        @SerializedName("beatmap_id")
        private long beatmapId;

        @SerializedName("start_time")
        private String startTime;

        @SerializedName("end_time")
        private String endTime;

        private String mode;

        @SerializedName("scoring_type")
        private String scoringType;

        @SerializedName("team_type")
        private String teamType;

        private BeatmapExtended beatmap;
        private List<JsonObject> scores;
    }
}
