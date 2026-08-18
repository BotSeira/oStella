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
            players.add(new MultiplayerResultData.PlayerResult(
                    position,
                    score.getUserId() == null ? user == null ? 0 : user.getId() : score.getUserId(),
                    user == null || user.getUsername() == null || user.getUsername().isBlank()
                            ? "Player #" + score.getUserId()
                            : user.getUsername(),
                    user == null || user.getAvatarUrl() == null || user.getAvatarUrl().isBlank()
                            ? null
                            : user.getAvatarUrl(),
                    user == null ? null : user.getCountryCode(),
                    playerMap == null || playerMap.getVersion() == null ? "Unknown Diff" : playerMap.getVersion(),
                    playerMap == null ? null : playerMap.getDifficultyRating(),
                    modString(score.getMods()),
                    score.getAccuracy(),
                    score.getMaxCombo(),
                    score.getTotalScore(),
                    score.getPp(),
                    score.getRank() == null ? "-" : score.getRank(),
                    Boolean.TRUE.equals(score.getPassed())
            ));
            fallbackPosition++;
        }

        return new MultiplayerResultData(
                room.getId(),
                room.getName() == null ? "MP #" + room.getId() : room.getName(),
                item.getId(),
                item.getPlayedAt(),
                mapInfo,
                queuedBy,
                players
        );
    }

    private static MultiplayerResultData.BeatmapInfo toBeatmapInfo(long beatmapId, BeatmapExtended map) {
        Beatmapset set = map == null ? null : map.getBeatmapset();
        Beatmap.Covers covers = set == null ? null : set.getCovers();
        String coverUrl = covers == null
                ? null
                : firstNonBlankOrNull(covers.getCover2x(), covers.getCover(), covers.getCard2x(), covers.getCard());
        return new MultiplayerResultData.BeatmapInfo(
                map == null || map.getId() == null ? beatmapId : map.getId(),
                set == null ? "Beatmap #" + beatmapId : firstNonBlank(set.getTitleUnicode(), set.getTitle()),
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
