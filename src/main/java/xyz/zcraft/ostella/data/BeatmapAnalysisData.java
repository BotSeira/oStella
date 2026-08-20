package xyz.zcraft.ostella.data;

import xyz.zcraft.ostella.network.PerfPlusApi;
import xyz.zcraft.osu.model.BeatmapExtended;
import xyz.zcraft.osu.parser.data.beatmap.BeatmapPatternAnalysis;
import xyz.zcraft.osu.parser.data.beatmap.DiffSpec;

import java.util.Comparator;
import java.util.List;

public record BeatmapAnalysisData(
        BeatmapExtended beatmap,
        DiffSpec diff,
        List<String> mods,
        PerfPlusApi.PerformancePlus performance,
        BeatmapPatternAnalysis patterns
) {
    public PatternView primaryType() {
        return patternView(patterns.primaryType());
    }

    public AimPatternView primaryAimType() {
        return aimPatternView(patterns.primaryAimType());
    }

    public List<PatternView> types() {
        return patterns.types().stream().map(BeatmapAnalysisData::patternView).toList();
    }

    public List<AimPatternView> aimTypes() {
        return patterns.aimTypes().stream()
                .filter(type -> type.evidence() > 0)
                .map(BeatmapAnalysisData::aimPatternView)
                .sorted(Comparator.comparingDouble(AimPatternView::percentage).reversed())
                .toList();
    }

    public List<PerfPlusApi.SkillPerformance> skillsByPercentage() {
        return performance.skills().stream()
                .sorted(Comparator.comparingDouble(PerfPlusApi.SkillPerformance::percentage).reversed())
                .toList();
    }

    public PerfPlusApi.SkillPerformance primarySkill() {
        return performance.skills().stream()
                .max(java.util.Comparator.comparingDouble(PerfPlusApi.SkillPerformance::pp))
                .orElseThrow();
    }

    public boolean hasMods() {
        return mods != null && !mods.isEmpty();
    }

    public boolean hasAimEvidence() {
        return patterns.primaryAimType().evidence() > 0;
    }

    private static PatternView patternView(BeatmapPatternAnalysis.PatternScore score) {
        return switch (score.type()) {
            case STREAM -> new PatternView("Stream", score.percentage(), "#4a90e2",
                    "Long rapid circle runs detected from object timing");
            case ALT -> new PatternView("Alt", score.percentage(), "#b388ff",
                    "Sustained alternating rhythm patterns");
            case AIM -> new PatternView("Aim", score.percentage(), "#ff6b8a",
                    "Spatial movement pressure from object spacing and timing");
            case FLOW -> new PatternView("Flow", score.percentage(), "#2fd4c7",
                    "Continuous movement with consistent direction and velocity");
            case TECH -> new PatternView("Tech", score.percentage(), "#9cff22",
                    "Sharp movement changes, slider complexity and spacing variation");
            case READING -> new PatternView("Reading", score.percentage(), "#ffd166",
                    "Overlap, rhythm complexity and low-AR visibility pressure");
        };
    }

    private static AimPatternView aimPatternView(BeatmapPatternAnalysis.AimPatternScore score) {
        return switch (score.type()) {
            case SNAP_AIM -> new AimPatternView("Snap Aim", score.percentage(),
                    "Separated targets that encourage discrete acceleration and braking");
            case JUMP_AIM -> new AimPatternView("Jump Aim", score.percentage(),
                    "General spacing-driven cursor movement");
            case CROSS_SCREEN_JUMP_AIM -> new AimPatternView("Cross-screen Jump Aim", score.percentage(),
                    "Jumps spanning a large portion of the playfield");
            case AWKWARD_AIM -> new AimPatternView("Awkward Aim", score.percentage(),
                    "Aim control under sharp or unnatural direction changes");
            case FLOW_AIM -> new AimPatternView("Flow Aim", score.percentage(),
                    "Aim performed through continuous cursor movement");
            case LINEAR_JUMP_AIM -> new AimPatternView("Linear Jump Aim", score.percentage(),
                    "Successive jumps following a nearly straight direction");
            case WIDE_ANGLE_JUMP_AIM -> new AimPatternView("Wide-angle Jump Aim", score.percentage(),
                    "Jumps connected by open, flowing angles");
            case SHARP_ANGLE_JUMP_AIM -> new AimPatternView("Sharp-angle Jump Aim", score.percentage(),
                    "Jumps that repeatedly demand strong direction changes");
            case BACK_AND_FORTH_AIM -> new AimPatternView("Back-and-forth Aim", score.percentage(),
                    "Repeated reversals between opposing directions");
        };
    }

    public record PatternView(
            String name,
            double percentage,
            String color,
            String description
    ) {
    }

    public record AimPatternView(String name, double percentage, String description) {
    }
}
