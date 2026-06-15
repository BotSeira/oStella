package xyz.zcraft.ostella.util.format;

import xyz.zcraft.ostella.util.Colors;
import xyz.zcraft.osu.parser.data.beatmap.DiffSpec;

public class DiffSpecFormatUtil {
    public static String getDiffColor(DiffSpec diffSpec) {
        return Colors.getBeatmapDiffColor(diffSpec.getStar());
    }

    public static String getDiffColorReadable(DiffSpec diffSpec) {
        return Colors.getBeatmapDiffColor(Math.clamp(diffSpec.getStar(), 0, 6.5));
    }

    public static String getDiffTextColor(DiffSpec diffSpec) {
        return Colors.getBeatmapDiffTextColor(diffSpec.getStar());
    }

    public static String getLengthStr(DiffSpec diffSpec) {
        final double length = diffSpec.getLength();
        return String.format("%01d", (int) (length / 60)) + ":" + String.format("%02d", (int) (length % 60));
    }

    public static String getTotalLengthStr(DiffSpec diffSpec) {
        final double totalLength = diffSpec.getTotalLength();
        return String.format("%01d", (int) (totalLength / 60)) + ":" + String.format("%02d", (int) (totalLength % 60));
    }

    public static String getDiffChangeStr(double diff) {
        if (diff < 0.1 && diff > -0.1) return "~0.00";
        else if (diff > 0) return "▲" + "%.2f".formatted(diff);
        else return "▼" + "%.2f".formatted(-diff);
    }

    public static String getIntDiffChangeStr(double diff) {
        if (diff < 0.1 && diff > -0.1) return "~0";
        else if (diff > 0) return "▲" + (int) diff;
        else return "▼" + (int) (-diff);
    }

    public static String getDiffChangeClassSuffix(double diff) {
        if (diff < 0.1 && diff > -0.1) return "-same";
        else if (diff > 0) return "-up";
        else return "-down";
    }

    public static String getODString(DiffSpec diffSpec) {
        return "±" + String.format("%.2f", diffSpec.getDifficulty().getPerfectWindow()) + "ms";
    }

    public static String getHPString(DiffSpec diffSpec) {
        final double hp = diffSpec.getDifficulty().hp();
        if (hp <= 4) return "awa";
        else if (hp <= 8) return "owo";
        else return "qwq";
    }

    public static String getARString(DiffSpec diffSpec) {
        final double ar = diffSpec.getDifficulty().ar();
        if (ar < 5) {
            return (int) (1200 + 120 * (5 - ar)) + "ms";
        } else if (ar == 5) {
            return "1200ms";
        } else {
            return (int) (1200 - 150 * (ar - 5)) + "ms";
        }
    }

    public static String getCSString(DiffSpec diffSpec) {
        final double cs = diffSpec.getDifficulty().cs();
        return (int) (54.4 - 4.48 * cs) + "px";
    }
}
