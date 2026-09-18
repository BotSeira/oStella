package xyz.zcraft.ostella.data;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

public record MultiplayerResultData(long roomId, String roomName, long playlistItemId, String playedAt,
                                    String client, String scoringType, String teamType,
                                    long totalScore, long averageScore, double teamLeadPercent, String winningTeam,
                                    SeriesScore seriesScore,
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
            SeriesScore seriesScore,
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
        this.seriesScore = seriesScore == null ? SeriesScore.empty() : seriesScore;
        this.beatmap = beatmap;
        this.queuedBy = queuedBy;
        this.players = List.copyOf(players);
        this.teams = List.copyOf(teams);
        this.unassignedPlayers = List.copyOf(unassignedPlayers);
    }

    private static int teamOrder(String team) {
        return switch (team == null ? "" : team) {
            case "red" -> 0;
            case "blue" -> 1;
            default -> 2;
        };
    }

    public boolean isTeamVs() {
        return teams.size() == 2 && !isTeamDuel();
    }

    public boolean isDuel() {
        return isTeamDuel() || (teams.isEmpty() && players.size() == 2);
    }

    public List<PlayerResult> duelPlayers() {
        if (!isDuel()) {
            return players;
        }
        return players.stream()
                .sorted(Comparator
                        .comparingInt((PlayerResult player) -> teamOrder(player.team()))
                        .thenComparingLong(PlayerResult::userId))
                .toList();
    }

    public List<VersusScore> versusScores() {
        if (teams.size() == 2) {
            return teams.stream()
                    .map(team -> new VersusScore(
                            team.key(),
                            team.name(),
                            "red".equals(team.key()) ? seriesScore.redWins() : seriesScore.blueWins()
                    ))
                    .toList();
        }
        if (!isDuel()) {
            return List.of();
        }
        return duelPlayers().stream()
                .map(player -> new VersusScore(
                        null,
                        player.username(),
                        seriesScore.playerWins().getOrDefault(player.userId(), 0)
                ))
                .toList();
    }

    private boolean isTeamDuel() {
        return players.size() == 2
                && teams.size() == 2
                && teams.get(0).players().size() == 1
                && teams.get(1).players().size() == 1;
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

    public record SeriesScore(Map<Long, Integer> playerWins, int redWins, int blueWins) {
        public SeriesScore {
            playerWins = playerWins == null ? Map.of() : Map.copyOf(playerWins);
        }

        public static SeriesScore empty() {
            return new SeriesScore(Map.of(), 0, 0);
        }
    }

    public record VersusScore(String key, String name, int wins) {
    }
}
