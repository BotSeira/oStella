package xyz.zcraft.ostella.data;

import lombok.Getter;
import lombok.Setter;
import xyz.zcraft.osu.model.Score;

@Getter
public final class ScoreEntry {
    private final int bestIndex;
    private final Score score;

    @Setter
    private String extra;

    public ScoreEntry(int bestIndex, Score score, String extra) {
        this.bestIndex = bestIndex;
        this.score = score;
        this.extra = extra;
    }

    public ScoreEntry(int bestIndex, Score score) {
        this(bestIndex, score, null);
    }
}
