package xyz.zcraft.ostella.data;

import java.util.List;

public record MultiplayerResultData(long roomId, String roomName, long playlistItemId, String playedAt,
                                    String client, String scoringType, String teamType,
                                    long totalScore, long averageScore, double teamLeadPercent, String winningTeam,
                                    BeatmapInfo beatmap, UserInfo queuedBy, List<PlayerResult> players,
                                    List<TeamResult> teams, List<PlayerResult> unassignedPlayers) {
    public MultiplayerResultData(
            long roomId,
            String roomName,
            long playlistItemId,
            String playedAt,
            String client,
            String scoringType,
            String teamType,
            long totalScore,
            long averageScore,
            double teamLeadPercent,
            String winningTeam,
            BeatmapInfo beatmap,
            UserInfo queuedBy,
            List<PlayerResult> players,
            List<TeamResult> teams,
            List<PlayerResult> unassignedPlayers
    ) {
        this.roomId = roomId;
        this.roomName = roomName;
        this.playlistItemId = playlistItemId;
        this.playedAt = playedAt;
        this.client = client;
        this.scoringType = scoringType;
        this.teamType = teamType;
        this.totalScore = totalScore;
        this.averageScore = averageScore;
        this.teamLeadPercent = teamLeadPercent;
        this.winningTeam = winningTeam;
        this.beatmap = beatmap;
        this.queuedBy = queuedBy;
        this.players = List.copyOf(players);
        this.teams = List.copyOf(teams);
        this.unassignedPlayers = List.copyOf(unassignedPlayers);
    }

    public boolean isTeamVs() {
        return teams.size() == 2;
    }

    public boolean isDuel() {
        return !isTeamVs() && players.size() == 2;
    }

    public record BeatmapInfo(long id, String title, String titleUnicode, String artist, String creator,
                              String difficultyName,
                              Double difficultyRating, Double bpm, Long totalLength, String ruleset, String coverUrl) {
    }

    public record UserInfo(long id, String username, String avatarUrl) {
    }

    public record PlayerResult(int position, long userId, String username, String avatarUrl,
                               String profileCoverUrl, String countryCode, String difficultyName,
                               Double difficultyRating, String mods, Double accuracy,
                               Long maxCombo, Long totalScore, Double pp, String grade, boolean passed,
                               long misses, Long scoreGap, String team) {
    }

    public record TeamResult(String key, String name, long totalScore, List<PlayerResult> players) {
        public TeamResult {
            players = List.copyOf(players);
        }
    }
}
