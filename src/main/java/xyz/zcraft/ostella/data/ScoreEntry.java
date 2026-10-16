package xyz.zcraft.ostella.data;

import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import xyz.zcraft.osu.model.Score;

import java.util.LinkedList;
import java.util.List;

@Getter
public final class ScoreEntry {
    private final int bestIndex;
    private final Score score;

    @NotNull
    @Setter
    private List<String> extras;

    public ScoreEntry(int bestIndex, Score score) {
        this.bestIndex = bestIndex;
        this.score = score;
        this.extras = new LinkedList<>();
    }

    public String getExtraString() {
        if (extras.isEmpty()) {
            return "";
        }

        return String.join(", ", extras);
    }

    public void addExtra(String extra) {
        extras.add(extra);
    }
}
