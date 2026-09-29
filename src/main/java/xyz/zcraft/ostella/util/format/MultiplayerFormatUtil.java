package xyz.zcraft.ostella.util.format;

import xyz.zcraft.ostella.data.MultiplayerResultData;

public class MultiplayerFormatUtil {
    public static int getLeftLoseIndicatorCount(MultiplayerResultData result) {
        final var leftScore = result.versusScores().getFirst();
        final var rightScore = result.versusScores().getLast();
        if (result.customBo() != null) {
            return Math.max((result.customBo() + 1) / 2 - leftScore.wins(), 0);
        } else {
            return Math.max(rightScore.wins() - leftScore.wins() + 1, 1);
        }
    }

    public static int getRightLoseIndicatorCount(MultiplayerResultData result) {
        final var leftScore = result.versusScores().getFirst();
        final var rightScore = result.versusScores().getLast();
        if (result.customBo() != null) {
            return Math.max((result.customBo() + 1) / 2 - rightScore.wins(), 0);
        } else {
            return Math.max(leftScore.wins() - rightScore.wins() + 1, 1);
        }
    }
}
