package xyz.zcraft.ostella.data;

import xyz.zcraft.ostella.db.ScoreStore;
import xyz.zcraft.osu.model.Mod;
import xyz.zcraft.osu.model.Score;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ScoreRecord(
        long scoreId,
        String source,
        long userId,
        long beatmapId,
        Long playedAt,
        List<Mod> mods,
        Score.ScoreStatistics statistics,
        Score.ScoreStatistics maximumStatistics,
        Long maxCombo,
        Boolean passed,
        Boolean ranked,
        Long buildId,
        String beatmapMd5,
        String replayHash,

        Long totalScore,
        Double pp,
        Double accuracy,
        String grade,

        String metricsRevision,
        Long calculatedAt,
        long createdAt,
        long updatedAt
) {
    public static ScoreRecord fromScore(Score score) {
        return fromScore(score, ScoreStore.METRICS_REVISION);
    }

    public static ScoreRecord fromScore(Score score, String revision) {
        Objects.requireNonNull(score);

        Long id = score.getId();
        Long userId = score.getUserId();
        Long beatmapId = score.getBeatmapId();

        if (id == null || id == 0 || userId == null
                || userId < 0 || beatmapId == null || beatmapId <= 0) {
            throw new IllegalArgumentException(
                    "Score requires nonzero ID, valid user ID and beatmap ID");
        }

        Long playedAt = null;
        if (score.getEndedAt() != null) {
            playedAt = Instant.parse(score.getEndedAt()).toEpochMilli();
        }

        long now = System.currentTimeMillis();

        return new ScoreRecord(
                id,
                id < 0 ? "local" : "online",
                userId,
                beatmapId,
                playedAt,
                score.getMods() == null ? List.of() : List.copyOf(score.getMods()),
                score.getStatistics() == null
                        ? new Score.ScoreStatistics()
                        : score.getStatistics(),
                score.getMaximumStatistics(),
                score.getMaxCombo(),
                score.getPassed(),
                score.getRanked(),
                score.getBuildId(),
                score.getBeatmap() == null
                        ? null : score.getBeatmap().getChecksum(),
                null, // Replay hash isn't a field in osuModel.Score.
                score.getTotalScore(),
                score.getPp(),
                score.getAccuracy(),
                score.getRank(),
                score.getTotalScore() == null ? null : revision,
                score.getTotalScore() == null ? null : now,
                now,
                now
        );
    }

    public boolean isStale() {
        return !ScoreStore.METRICS_REVISION.equals(metricsRevision);
    }

    public Score toScore() {
        Score score = new Score();
        score.setId(scoreId);
        score.setUserId(userId);
        score.setBeatmapId(beatmapId);
        score.setMods(mods);
        score.setStatistics(statistics);
        score.setMaximumStatistics(maximumStatistics);
        score.setMaxCombo(maxCombo);
        score.setPassed(passed);
        score.setRanked(ranked);
        score.setBuildId(buildId);
        score.setTotalScore(totalScore);
        score.setPp(pp);
        score.setAccuracy(accuracy);
        score.setRank(grade);
        score.setEndedAt(playedAt == null
                ? null : Instant.ofEpochMilli(playedAt).toString());
        return score;
    }

    public boolean isRanked() {
        return ranked != null && ranked;
    }

    public boolean isLocal() {
        return scoreId < 0;
    }
}
