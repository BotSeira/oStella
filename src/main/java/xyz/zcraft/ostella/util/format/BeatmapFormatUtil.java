package xyz.zcraft.ostella.util.format;

import xyz.zcraft.ostella.data.BeatmapAnalysisData;
import xyz.zcraft.osu.model.Beatmap;
import xyz.zcraft.osu.model.BeatmapExtended;
import xyz.zcraft.osu.model.Beatmapset;
import xyz.zcraft.osu.parser.data.beatmap.BeatmapPatternAnalysis;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class BeatmapFormatUtil {
    public static boolean hasLeaderboard(Beatmap beatmap) {
        return Optional.ofNullable(beatmap.getStatus())
                .map(s -> "RANKED".equalsIgnoreCase(s) || "LOVED".equalsIgnoreCase(s))
                .orElse(false);
    }

    public static String getOwnersString(BeatmapExtended beatmap) {
        return beatmap.getOwners().stream()
                .map(owner -> owner.username)
                .collect(Collectors.joining(", "));
    }

    public static double getPassRate(BeatmapExtended beatmap) {
        final Long passcount = beatmap.getPasscount();
        final Long playcount = beatmap.getPlaycount();
        if (passcount == null || playcount == null) return 0;
        if (playcount == 0) return 0;
        return ((double) passcount / playcount) * 100.0;
    }

    public static String getTagString(BeatmapExtended beatmap) {
        return Optional.ofNullable(beatmap.getBeatmapset())
                .map(Beatmapset::getTags)
                .map(t -> t.substring(0, Math.min(t.length(), 80)) + (t.length() > 80 ? "..." : ""))
                .orElse("");
    }

    public static String getLowPercentageTypeString(List<BeatmapAnalysisData.PatternView> types, double threshold) {
        return types.stream()
                .filter(type -> type.percentage() <= threshold)
                .map(p -> "%s(%.1f%%)".formatted(p.name(), p.percentage()))
                .collect(Collectors.joining(", "));
    }

    public static boolean hasLowPercentageResult(List<BeatmapAnalysisData.PatternView> types, double threshold) {
        return types.stream()
                .anyMatch(type -> type.percentage() <= threshold);
    }

    public static boolean doShowAimBreakdown(BeatmapAnalysisData analysisData) {
        if (analysisData == null) return false;
        if (!analysisData.hasAimEvidence()) return false;
        if (analysisData.patterns().primaryType().type().equals(BeatmapPatternAnalysis.PatternType.AIM)) return true;

        return analysisData.patterns().types()
                .stream()
                .filter(t -> t.type().equals(BeatmapPatternAnalysis.PatternType.AIM))
                .findFirst()
                .map(t -> t.percentage() > 10)
                .orElse(false);
    }
}
