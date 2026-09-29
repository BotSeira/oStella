package xyz.zcraft.ostella.data;

import xyz.zcraft.ostella.network.PerfPlusApi;
import xyz.zcraft.osu.model.Score;
import xyz.zcraft.osu.parser.data.beatmap.DiffSpec;
import xyz.zcraft.osu.parser.data.replay.ReplayAnalyze;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

public record ScoreAnalysisData(
        Score score,
        DiffSpec diffSpec,
        List<Long> hitErrors,
        List<double[]> hitPositions,
        List<double[]> hitPositionsAbsolute,
        List<double[]> missPositions,
        List<double[]> missPositionsAbsolute,
        double aimBias,
        double avgTimingError,
        ReplayAnalyze replayAnalyze,
        PerformanceGraphData performanceGraph,
        PerfPlusApi.PerformancePlus performancePlus,
        boolean isLazerScore,
        boolean doSimMatch,
        String simHitResult
) {
    /** Public analysis data excludes the parser's full beatmap and replay object graph. */
    public Map<String, Object> responseData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("score", score);
        data.put("diffSpec", diffSpec);
        data.put("hitErrors", hitErrors);
        data.put("hitPositions", hitPositions);
        data.put("hitPositionsAbsolute", hitPositionsAbsolute);
        data.put("missPositions", missPositions);
        data.put("missPositionsAbsolute", missPositionsAbsolute);
        data.put("aimBias", aimBias);
        data.put("avgTimingError", avgTimingError);
        data.put("unstableRate", replayAnalyze.unstableRate());
        data.put("aimUnstableRate", replayAnalyze.aimUnstableRate());
        data.put("performanceGraph", performanceGraph);
        data.put("performancePlus", performancePlus);
        data.put("isLazerScore", isLazerScore);
        data.put("doSimMatch", doSimMatch);
        data.put("simHitResult", simHitResult);
        return data;
    }
}

