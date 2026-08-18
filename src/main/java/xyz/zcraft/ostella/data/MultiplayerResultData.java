package xyz.zcraft.ostella.data;

import lombok.Getter;

import java.util.List;

@Getter
public final class MultiplayerResultData {
    private final long roomId;
    private final String roomName;
    private final long playlistItemId;
    private final String playedAt;
    private final BeatmapInfo beatmap;
    private final UserInfo queuedBy;
    private final List<PlayerResult> players;

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

    @Getter
    public static final class BeatmapInfo {
        private final long id;
        private final String title;
        private final String artist;
        private final String creator;
        private final String difficultyName;
        private final Double difficultyRating;
        private final Double bpm;
        private final Long totalLength;
        private final String ruleset;
        private final String coverUrl;

        public BeatmapInfo(
                long id,
                String title,
                String artist,
                String creator,
                String difficultyName,
                Double difficultyRating,
                Double bpm,
                Long totalLength,
                String ruleset,
                String coverUrl
        ) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.creator = creator;
            this.difficultyName = difficultyName;
            this.difficultyRating = difficultyRating;
            this.bpm = bpm;
            this.totalLength = totalLength;
            this.ruleset = ruleset;
            this.coverUrl = coverUrl;
        }
    }

    @Getter
    public static final class UserInfo {
        private final long id;
        private final String username;
        private final String avatarUrl;

        public UserInfo(long id, String username, String avatarUrl) {
            this.id = id;
            this.username = username;
            this.avatarUrl = avatarUrl;
        }
    }

    @Getter
    public static final class PlayerResult {
        private final int position;
        private final long userId;
        private final String username;
        private final String avatarUrl;
        private final String countryCode;
        private final String difficultyName;
        private final Double difficultyRating;
        private final String mods;
        private final Double accuracy;
        private final Long maxCombo;
        private final Long totalScore;
        private final Double pp;
        private final String grade;
        private final boolean passed;

        public PlayerResult(
                int position,
                long userId,
                String username,
                String avatarUrl,
                String countryCode,
                String difficultyName,
                Double difficultyRating,
                String mods,
                Double accuracy,
                Long maxCombo,
                Long totalScore,
                Double pp,
                String grade,
                boolean passed
        ) {
            this.position = position;
            this.userId = userId;
            this.username = username;
            this.avatarUrl = avatarUrl;
            this.countryCode = countryCode;
            this.difficultyName = difficultyName;
            this.difficultyRating = difficultyRating;
            this.mods = mods;
            this.accuracy = accuracy;
            this.maxCombo = maxCombo;
            this.totalScore = totalScore;
            this.pp = pp;
            this.grade = grade;
            this.passed = passed;
        }
    }
}
