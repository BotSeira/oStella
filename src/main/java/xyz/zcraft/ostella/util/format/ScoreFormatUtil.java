package xyz.zcraft.ostella.util.format;

import xyz.zcraft.ostella.service.CacheService;
import xyz.zcraft.ostella.util.Colors;
import xyz.zcraft.ostella.util.MiscUtil;
import xyz.zcraft.ostella.data.ScoreId;
import xyz.zcraft.osu.model.Mod;
import xyz.zcraft.osu.model.Score;

public class ScoreFormatUtil {
    public static String getRelativeTime(Score score) {
        if (score == null || score.getEndedAt() == null) {
            return "";
        }
        return MiscUtil.getRelativeTimeAgo(score.getEndedAt());
    }

    public static String getScoreStatus(Score score) {
        if (ScoreId.isLocal(score)) {
            return "LOC";
        }
        if (score.getRanked() == false) {
            return "×";
        }
        if ("LOVED".equalsIgnoreCase(score.getBeatmap().getStatus())) {
            return "❤";
        } else if ("RANKED".equalsIgnoreCase(score.getBeatmap().getStatus())) {
            return "▲";
        }
        return "?";
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
            sb.append(mod.getAcronym());
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
        return score.getHasReplay() || CacheService.hasReplayCache(score.getId());
    }
}



