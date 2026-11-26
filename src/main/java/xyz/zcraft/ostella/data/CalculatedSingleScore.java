package xyz.zcraft.ostella.data;

import com.google.gson.annotations.SerializedName;
import xyz.zcraft.osu.model.Mod;
import xyz.zcraft.osu.model.Score;
import xyz.zcraft.osu.parser.data.PerformanceAttributes;
import xyz.zcraft.osu.parser.data.beatmap.DifficultyAttributes;

import java.util.List;

public record CalculatedSingleScore(
        SingleScore score,
        @SerializedName("performance_attributes") PerformanceAttributes performanceAttributes,
        @SerializedName("difficulty_attributes") DifficultyAttributes difficultyAttributes
) {
    public record SingleScore(
            @SerializedName("ruleset_id") Long rulesetId,
            @SerializedName("beatmap_id") Long beatmapId,
            @SerializedName("beatmap") String beatmap,
            @SerializedName("mods") List<Mod> mods,
            @SerializedName("total_score") Long totalScore,
            @SerializedName("legacy_total_score") Long legacyTotalScore,
            @SerializedName("accuracy") Double accuracy,
            @SerializedName("combo") Integer combo,
            @SerializedName("statistics") Score.ScoreStatistics statistics
    ) {
    }
}
