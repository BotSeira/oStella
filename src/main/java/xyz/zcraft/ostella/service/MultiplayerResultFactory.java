package xyz.zcraft.ostella.service;

import xyz.zcraft.ostella.data.MultiplayerResultData;
import xyz.zcraft.ostella.data.MultiplayerRoomDetails;
import xyz.zcraft.ostella.data.MultiplayerRoomScore;
import xyz.zcraft.osu.model.Beatmap;
import xyz.zcraft.osu.model.BeatmapExtended;
import xyz.zcraft.osu.model.Beatmapset;
import xyz.zcraft.osu.model.Mod;
import xyz.zcraft.osu.model.Score;
import xyz.zcraft.osu.model.User;
import xyz.zcraft.osu.model.UserExtended;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class MultiplayerResultFactory {
    private MultiplayerResultFactory() {
    }

    public static MultiplayerResultData create(
            MultiplayerRoomDetails room,
            MultiplayerRoomDetails.PlaylistItem item,
            List<MultiplayerRoomScore> roomScores,
            User owner
    ) {
        return create(room, item, roomScores, owner, "lazer", "scorev2", room.getType());
    }

    public static MultiplayerResultData create(
            MultiplayerRoomDetails room,
            MultiplayerRoomDetails.PlaylistItem item,
            List<MultiplayerRoomScore> roomScores,
            User owner,
            String client,
            String scoringType,
            String teamType
    ) {
        BeatmapExtended map = item.getBeatmap();
        MultiplayerResultData.BeatmapInfo mapInfo = toBeatmapInfo(item.getBeatmapId(), map);
        MultiplayerResultData.UserInfo queuedBy = toUserInfo(owner, item.getOwnerId());

        List<MultiplayerRoomScore> sorted = new ArrayList<>(roomScores);
        sorted.sort(Comparator
                .comparing(MultiplayerRoomScore::position, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(
                        value -> value.score() == null ? null : value.score().getTotalScore(),
                        Comparator.nullsLast(Comparator.reverseOrder())
                ));

        List<MultiplayerResultData.PlayerResult> players = new ArrayList<>(sorted.size());
        int fallbackPosition = 1;
        Long previousScore = null;
        for (MultiplayerRoomScore roomScore : sorted) {
            Score score = roomScore.score();
            if (score == null) {
                continue;
            }
            BeatmapExtended playerMap = score.getBeatmap() == null ? map : score.getBeatmap();
            User user = score.getUser();
            int position = roomScore.position() == null || roomScore.position() <= 0
                    ? fallbackPosition
                    : roomScore.position();
            Long totalScore = score.getTotalScore();
            Long scoreGap = previousScore == null || totalScore == null
                    ? null
                    : Math.abs(previousScore - totalScore);
            players.add(new MultiplayerResultData.PlayerResult(
                    position,
                    score.getUserId() == null ? user == null ? 0 : user.getId() : score.getUserId(),
                    user == null || user.getUsername() == null || user.getUsername().isBlank()
                            ? "Player #" + score.getUserId()
                            : user.getUsername(),
                    user == null || user.getAvatarUrl() == null || user.getAvatarUrl().isBlank()
                            ? null
                            : user.getAvatarUrl(),
                    profileCoverUrl(user),
                    user == null ? null : user.getCountryCode(),
                    playerMap == null || playerMap.getVersion() == null ? "Unknown Diff" : playerMap.getVersion(),
                    playerMap == null ? null : playerMap.getDifficultyRating(),
                    modString(score.getMods()),
                    score.getAccuracy(),
                    score.getMaxCombo(),
                    totalScore,
                    score.getPp(),
                    score.getRank() == null ? "-" : score.getRank(),
                    Boolean.TRUE.equals(score.getPassed()),
                    statistic(score, "miss", "count_miss"),
                    scoreGap,
                    normalizeTeam(roomScore.team())
            ));
            if (totalScore != null) {
                previousScore = totalScore;
            }
            fallbackPosition++;
        }

        long roundTotal = players.stream()
                .map(MultiplayerResultData.PlayerResult::totalScore)
                .filter(value -> value != null)
                .mapToLong(Long::longValue)
                .sum();
        long scoredPlayers = players.stream()
                .filter(player -> player.totalScore() != null)
                .count();
        long roundAverage = scoredPlayers == 0 ? 0 : Math.round((double) roundTotal / scoredPlayers);

        boolean teamVs = isTeamVs(teamType);
        Comparator<MultiplayerResultData.PlayerResult> teamOrder = Comparator
                .comparing(
                        MultiplayerResultData.PlayerResult::totalScore,
                        Comparator.nullsLast(Comparator.reverseOrder())
                )
                .thenComparingInt(MultiplayerResultData.PlayerResult::position);
        List<MultiplayerResultData.PlayerResult> redPlayers = teamVs
                ? players.stream().filter(player -> "red".equals(player.team())).sorted(teamOrder).toList()
                : List.of();
        List<MultiplayerResultData.PlayerResult> bluePlayers = teamVs
                ? players.stream().filter(player -> "blue".equals(player.team())).sorted(teamOrder).toList()
                : List.of();
        List<MultiplayerResultData.PlayerResult> unassignedPlayers = teamVs
                ? players.stream().filter(player -> player.team() == null).sorted(teamOrder).toList()
                : List.of();
        long redTotal = teamTotal(redPlayers);
        long blueTotal = teamTotal(bluePlayers);
        List<MultiplayerResultData.TeamResult> teams = teamVs
                ? List.of(
                        new MultiplayerResultData.TeamResult("red", "Red Team", redTotal, redPlayers),
                        new MultiplayerResultData.TeamResult("blue", "Blue Team", blueTotal, bluePlayers)
                )
                : List.of();
        String winningTeam = !teamVs || redTotal == blueTotal ? "tie" : redTotal > blueTotal ? "red" : "blue";
        long higherTeamScore = Math.max(redTotal, blueTotal);
        double teamLeadPercent = higherTeamScore == 0
                ? 0
                : Math.min(50, Math.sqrt(Math.abs(redTotal - blueTotal) / (double) roundAverage) * 75.0);

        return new MultiplayerResultData(
                room.getId(),
                room.getName() == null ? "MP #" + room.getId() : room.getName(),
                item.getId(),
                item.getPlayedAt(),
                clientLabel(client),
                scoringTypeLabel(scoringType),
                teamTypeLabel(teamType),
                roundTotal,
                roundAverage,
                teamLeadPercent,
                winningTeam,
                mapInfo,
                queuedBy,
                players,
                teams,
                unassignedPlayers
        );
    }

    private static long statistic(Score score, String... names) {
        if (score.getStatistics() == null) {
            return 0;
        }
        for (String name : names) {
            Long value = score.getStatistics().get(name);
            if (value != null) {
                return value;
            }
        }
        return 0;
    }

    private static long teamTotal(List<MultiplayerResultData.PlayerResult> players) {
        return players.stream()
                .map(MultiplayerResultData.PlayerResult::totalScore)
                .filter(value -> value != null)
                .mapToLong(Long::longValue)
                .sum();
    }

    private static boolean isTeamVs(String teamType) {
        if (teamType == null) {
            return false;
        }
        String normalized = teamType.toLowerCase(Locale.ROOT).replace('_', '-');
        return normalized.contains("team") && !normalized.contains("head");
    }

    private static String normalizeTeam(String team) {
        if (team == null || team.isBlank()) {
            return null;
        }
        return switch (team.toLowerCase(Locale.ROOT)) {
            case "red", "1" -> "red";
            case "blue", "2" -> "blue";
            default -> null;
        };
    }

    private static String profileCoverUrl(User user) {
        if (!(user instanceof UserExtended extended)) {
            return null;
        }
        UserExtended.Cover cover = extended.getCover();
        return firstNonBlankOrNull(
                extended.getCoverUrl(),
                cover == null ? null : cover.getCustomUrl(),
                cover == null ? null : cover.getUrl()
        );
    }

    private static String clientLabel(String client) {
        return client != null && client.equalsIgnoreCase("stable") ? "Stable" : "Lazer";
    }

    private static String scoringTypeLabel(String scoringType) {
        if (scoringType == null || scoringType.isBlank()) {
            return "ScoreV1";
        }
        return switch (scoringType.toLowerCase(Locale.ROOT).replace("_", "")) {
            case "scorev2" -> "ScoreV2";
            case "accuracy" -> "Accuracy";
            case "combo" -> "Combo";
            default -> "ScoreV1";
        };
    }

    private static String teamTypeLabel(String teamType) {
        if (teamType == null || teamType.isBlank()) {
            return "Head to Head";
        }
        return switch (teamType.toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "team-vs", "team-versus" -> "Team VS";
            case "tag-team-vs", "tag-team-versus" -> "Tag Team VS";
            case "tag-coop", "tag-co-op" -> "Tag Co-op";
            default -> "Head to Head";
        };
    }

    private static MultiplayerResultData.BeatmapInfo toBeatmapInfo(long beatmapId, BeatmapExtended map) {
        Beatmapset set = map == null ? null : map.getBeatmapset();
        Beatmap.Covers covers = set == null ? null : set.getCovers();
        String coverUrl = covers == null
                ? null
                : firstNonBlankOrNull(covers.getCover2x(), covers.getCover(), covers.getCard2x(), covers.getCard());
        return new MultiplayerResultData.BeatmapInfo(
                map == null || map.getId() == null ? beatmapId : map.getId(),
                set == null ? "Beatmap #" + beatmapId : set.getTitle(),
                set == null ? "Beatmap #" + beatmapId : set.getTitleUnicode(),
                set == null ? "Unknown Artist" : firstNonBlank(set.getArtistUnicode(), set.getArtist()),
                set == null || set.getCreator() == null ? "Unknown Mapper" : set.getCreator(),
                map == null || map.getVersion() == null ? "Unknown Difficulty" : map.getVersion(),
                map == null ? null : map.getDifficultyRating(),
                map == null ? null : map.getBpm(),
                map == null ? null : map.getTotalLength(),
                map == null ? "osu!" : ruleset(map.getMode()),
                coverUrl
        );
    }

    private static MultiplayerResultData.UserInfo toUserInfo(User owner, long ownerId) {
        if (owner == null) {
            return new MultiplayerResultData.UserInfo(ownerId, "Player #" + ownerId, null);
        }
        return new MultiplayerResultData.UserInfo(
                owner.getId(),
                owner.getUsername() == null || owner.getUsername().isBlank()
                        ? "Player #" + owner.getId()
                        : owner.getUsername(),
                owner.getAvatarUrl() == null || owner.getAvatarUrl().isBlank() ? null : owner.getAvatarUrl()
        );
    }

    private static String modString(List<Mod> mods) {
        if (mods == null || mods.isEmpty()) {
            return "NM";
        }
        String result = mods.stream()
                .map(Mod::getAcronym)
                .filter(value -> value != null && !value.isBlank())
                .reduce("", String::concat);
        return result.isEmpty() ? "NM" : result;
    }

    private static String ruleset(String mode) {
        if (mode == null || mode.isBlank()) {
            return "osu!";
        }
        return switch (mode.toLowerCase(Locale.ROOT)) {
            case "osu" -> "osu!";
            case "taiko" -> "osu!taiko";
            case "fruits", "catch" -> "osu!catch";
            case "mania" -> "osu!mania";
            default -> mode;
        };
    }

    private static String firstNonBlank(String... values) {
        String value = firstNonBlankOrNull(values);
        return value == null ? "Unknown" : value;
    }

    private static String firstNonBlankOrNull(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
