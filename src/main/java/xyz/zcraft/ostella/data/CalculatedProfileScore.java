package xyz.zcraft.ostella.data;

import com.google.gson.annotations.SerializedName;
import xyz.zcraft.osu.model.Score;

import java.util.List;

public record CalculatedProfileScore(
        @SerializedName("LiveId") long liveId,
        @SerializedName("BeatmapId") long beatmapId,
        @SerializedName("BeatmapName") String beatmapName,
        @SerializedName("Rank") String rank,
        @SerializedName("Combo") int combo,
        @SerializedName("Accuracy") double accuracy,
        @SerializedName("MissCount") int missCount,
        @SerializedName("Count300") int count300,
        @SerializedName("Count100") int count100,
        @SerializedName("Count50") int count50,
        @SerializedName("Mods") List<String> mods,
        @SerializedName("Weight") Score.Weight weight,
        @SerializedName("LivePp") double livePp,
        @SerializedName("LocalPp") double localPp,
        @SerializedName("LocalStarRating") double localSr,
        @SerializedName("PositionChange") int positionChange,
        @SerializedName("EndedAt") String endedAt,
        @SerializedName("PerformanceBreakdown") PerformanceBreakdown performanceBreakdown
) {
}
