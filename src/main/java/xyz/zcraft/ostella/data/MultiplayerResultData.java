package xyz.zcraft.ostella.data;

import java.util.List;

public record MultiplayerResultData(long roomId, String roomName, long playlistItemId, String playedAt,
                                    BeatmapInfo beatmap, UserInfo queuedBy, List<PlayerResult> players) {
    public MultiplayerResultData(
            long roomId,
            String roomName,
            long playlistItemId,
            String playedAt,
            BeatmapInfo beatmap,
            UserInfo queuedBy,
            List<PlayerResult> players
    ) {
        this.roomId = roomId;
        this.roomName = roomName;
        this.playlistItemId = playlistItemId;
        this.playedAt = playedAt;
        this.beatmap = beatmap;
        this.queuedBy = queuedBy;
        this.players = List.copyOf(players);
    }

    public record BeatmapInfo(long id, String title, String titleUnicode, String artist, String creator,
                              String difficultyName,
                              Double difficultyRating, Double bpm, Long totalLength, String ruleset, String coverUrl) {
    }

    public record UserInfo(long id, String username, String avatarUrl) {
    }

    public record PlayerResult(int position, long userId, String username, String avatarUrl, String countryCode,
                               String difficultyName, Double difficultyRating, String mods, Double accuracy,
                               Long maxCombo, Long totalScore, Double pp, String grade, boolean passed) {
    }
}
