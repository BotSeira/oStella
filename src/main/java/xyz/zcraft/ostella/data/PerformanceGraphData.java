package xyz.zcraft.ostella.data;

import java.util.List;

public record PerformanceGraphData(
        List<double[]> windowDifficulties,
        List<double[]> realtimePp,
        List<Long> misses,
        List<Long> hit50s,
        List<Long> hit100s,
        List<Long> sliderTickBreaks,
        List<Long> sliderEndBreaks,
        long mapEndTime
) {
}
