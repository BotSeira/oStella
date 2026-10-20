package xyz.zcraft.ostella.util;

import xyz.zcraft.ostella.data.ScoreId;
import xyz.zcraft.ostella.service.CacheService;
import xyz.zcraft.osu.model.Mod;
import xyz.zcraft.osu.model.ModSettings;
import xyz.zcraft.osu.model.Score;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class Scores {
    public static final Set<String> RANKED_MODS = Set.of(
            "EZ", "NF", "HT", "DC",
            "HR", "SD", "PF", "DT", "NC", "HD", "TC", "FL", "BL", "AC",
            "SO", "AL", "SG", "MU", "NS", "TD"
    );

    public static boolean hasExtraHitStats(Score score) {
        if (score == null) {
            return false;
        }

        return getSliderTick(score) != 0 || getSliderTickMax(score) != 0
                || getSliderEnd(score) != 0 || getSliderEndMax(score) != 0
                || getSpinnerBonus(score) != 0 || getSpinnerBonusMax(score) != 0
                || getSpinnerSpin(score) != 0 || getSpinnerSpinMax(score) != 0;
    }

    public static String getRelativeTime(Score score) {
        if (score == null || score.getEndedAt() == null) {
            return "";
        }
        return MiscUtil.getRelativeTimeAgo(score.getEndedAt());
    }

    public static String getScoreStatusSymbol(Score score) {
        return switch (getRankStatus(score)) {
            case UNRANKED -> "×";
            case RANKED -> "▲";
            case LOCAL -> "LOC";
            case LOVED -> "❤";
            case null -> "?";
        };
    }

    public static boolean isLocal(Score score) {
        return ScoreId.isLocal(score);
    }

    public static String getScoreId(Score score) {
        return ScoreId.format(score);
    }

    public static String getDisplayScoreId(Score score) {
        String id = getScoreId(score);
        return ScoreId.isLocal(score) ? id : "S" + id;
    }

    public static boolean hasWeight(Score score) {
        return score != null && score.getWeight() != null
                && score.getWeight().getPercentage() != null
                && score.getWeight().getPp() != null;
    }

    public static String getRankColor(Score score) {
        return score == null ? "#d0d0d0" : Colors.getScoreRankColor(score.getRank());
    }

    public static String getRankBgColor(Score score) {
        return score == null ? "#d0d0d0" : Colors.getScoreRankBgColor(score.getRank());
    }

    public static boolean isRanked(Score score) {
        return score != null && score.getRanked() != null && score.getRanked() == true
                && isModsRankable(score);
    }


    public static RankStatus getRankStatus(Score score) {
        if (score == null) return null;

        if (isLocal(score)) return RankStatus.LOCAL;

        if (!Boolean.TRUE.equals(score.getPassed())
                || Boolean.FALSE.equals(score.getRanked())
                || !isModsRankable(score)
                || score.getBeatmap() == null
                || score.getBeatmap().getStatus() == null) {
            return RankStatus.UNRANKED;
        }

        return switch (score.getBeatmap().getStatus().toUpperCase(Locale.ROOT)) {
            case "RANKED", "APPROVED" -> RankStatus.RANKED;
            case "LOVED" -> RankStatus.LOVED;
            default -> RankStatus.UNRANKED;
        };
    }


    public static String getRankText(Score score) {
        if ("XH".equalsIgnoreCase(score.getRank())) {
            return "SS";
        } else if ("X".equalsIgnoreCase(score.getRank())) {
            return "SS";
        } else if ("SH".equalsIgnoreCase(score.getRank())) {
            return "S";
        } else {
            return score.getRank();
        }
    }

    public static String getModString(Score score) {
        if (score == null || score.getMods() == null || score.getMods().isEmpty()) {
            return "[NM]";
        }

        StringBuilder sb = new StringBuilder("[");
        for (Mod mod : score.getMods()) {
            sb.append(ModSettings.format(mod));
        }
        sb.append("]");

        return sb.toString();
    }

    public static String getHitResultString(Score score) {
        return getGreatCount(score) + " / " + getOkCount(score) + " / " + getMehCount(score) + " / " + getMissCount(score);
    }

    public static long getGreatCount(Score score) {
        return score.getStatistics().getOrDefault("great", 0L);
    }

    public static long getOkCount(Score score) {
        return score.getStatistics().getOrDefault("ok", 0L);
    }

    public static long getMehCount(Score score) {
        return score.getStatistics().getOrDefault("meh", 0L);
    }

    public static long getMissCount(Score score) {
        return score.getStatistics().getOrDefault("miss", 0L);
    }

    public static long getSpinnerBonus(Score score) {
        return score.getStatistics().getOrDefault("large_bonus", 0L);
    }

    public static long getSpinnerSpin(Score score) {
        return score.getStatistics().getOrDefault("small_bonus", 0L);
    }

    public static long getSliderTick(Score score) {
        return score.getStatistics().getOrDefault("large_tick_hit", 0L);
    }

    public static long getSliderEnd(Score score) {
        return score.getStatistics().getOrDefault("slider_tail_hit", 0L);
    }

    public static long getSpinnerBonusMax(Score score) {
        return score.getMaximumStatistics().getOrDefault("large_bonus", 0L);
    }

    public static long getSpinnerSpinMax(Score score) {
        return score.getMaximumStatistics().getOrDefault("small_bonus", 0L);
    }

    public static long getSliderTickMax(Score score) {
        return score.getMaximumStatistics().getOrDefault("large_tick_hit", 0L);
    }

    public static long getSliderEndMax(Score score) {
        return score.getMaximumStatistics().getOrDefault("slider_tail_hit", 0L);
    }

    public static boolean replayPresent(Score score) {
        if (score == null) return false;
        return (score.getHasReplay() != null && score.getHasReplay()) || CacheService.hasReplayCache(score.getId());
    }

    public static String getScoreRankColor(String status) {
        if (status == null) return "#d0d0d0";
        return switch (status.toUpperCase()) {
            case "RANKED" -> "#262639";
            case "UNRANKED" -> "#af002a";
            default -> "#d0a100";
        };
    }

    public static boolean shouldShowRankStatus(String status) {
        if (status == null) return false;
        return !status.equalsIgnoreCase("RANKED");
    }

    public static long getBeatmapId(Score score) {
        if (score.getBeatmapId() != null) {
            return score.getBeatmapId();
        } else if (score.getBeatmap() != null && score.getBeatmap().getId() != null) {
            return score.getBeatmap().getId();
        }

        throw new IllegalArgumentException("Score has no valid beatmap ID");
    }

    public static boolean isModsRankable(Score score) {
        if (score == null) return false;
        return isModsRankable(score.getMods(), score.getBuildId() == null);
    }

    public static boolean isModsRankable(List<Mod> mods, boolean allowCl) {
        if (mods == null || mods.isEmpty()) return true;

        return mods.stream().allMatch(m -> {
            String acronym = m.getAcronym();
            if (acronym == null) return false;


            acronym = acronym.toUpperCase(Locale.ROOT);

            if ("CL".equals(acronym)) return allowCl;

            if (!RANKED_MODS.contains(acronym)) return false;

            var settings = m.getSettings();

            return switch (acronym) {
                case "HT" -> hasDefaultSpeed(settings, 0.75, true);
                case "DC" -> hasDefaultSpeed(settings, 0.75, false);
                case "DT" -> hasDefaultSpeed(settings, 1.5, true);
                case "NC" -> hasDefaultSpeed(settings, 1.5, false);

                case "SD", "PF", "AC", "MU", "NS" -> true;

                default -> settings == null || settings.isEmpty();
            };
        });
    }

    public static boolean hasDefaultSpeed(
            Map<String, ?> settings,
            double defaultSpeed,
            boolean allowPitch
    ) {
        if (settings == null || settings.isEmpty()) return true;

        for (String key : settings.keySet()) {
            if (!key.equals("speed_change") && !(allowPitch && key.equals("adjust_pitch"))) {
                return false;
            }
        }

        Object speed = settings.get("speed_change");
        if (speed == null) return true;

        try {
            return Double.compare(Double.parseDouble(speed.toString()), defaultSpeed) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public enum RankStatus {
        LOCAL, RANKED, UNRANKED, LOVED
    }
}
