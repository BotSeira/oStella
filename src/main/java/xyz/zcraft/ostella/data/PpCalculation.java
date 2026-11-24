package xyz.zcraft.ostella.data;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public record PpCalculation(
        @SerializedName("Username") String username,
        @SerializedName("LivePp") double livePp,
        @SerializedName("LocalPp") double localPp,
        @SerializedName("PlaycountPp") double playcountPp,
        @SerializedName("Scores") List<CalculatedScore> scores,
        @SerializedName("WeightedPerformanceBreakdown") PerformanceBreakdown pp
) {
}
