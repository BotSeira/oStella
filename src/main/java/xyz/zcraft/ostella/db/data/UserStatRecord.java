package xyz.zcraft.ostella.db.data;

import xyz.zcraft.osu.model.User;
import xyz.zcraft.osu.model.UserExtended;

public record UserStatRecord(
        long userId,
        double pp,
        long globalRank,
        double accuracy,
        long playCount,
        long playTime,
        long totalScore,
        long totalHits,
        long updatedAt
) {
    public final static UserStatRecord INVALID = new UserStatRecord(0, 0.0, 0, 0.0, 0, 0, 0, 0, 0);

    public static UserStatRecord from(User user) {
        if (user == null || user.getId() <= 0) return INVALID;
        User.Statistics statistics = null;

        if (user instanceof UserExtended userExtended) {
            statistics = userExtended.getStatistics();
        }

        if (user.getStatisticsRulesets() != null && user.getStatisticsRulesets().getOsu() != null) {
            statistics = user.getStatisticsRulesets().getOsu();
        }

        if (statistics == null || statistics.getPp() == null || statistics.getGlobalRank() == null) {
            return INVALID;
        }

        return new UserStatRecord(
                user.getId(),
                statistics.getPp(),
                statistics.getGlobalRank(),
                statistics.getHitAccuracy() == null ? 0 : statistics.getHitAccuracy(),
                statistics.getPlayCount() == null ? 0 : statistics.getPlayCount(),
                statistics.getPlayTime() == null ? 0 : statistics.getPlayTime(),
                statistics.getTotalScore() == null ? 0 : statistics.getTotalScore(),
                statistics.getTotalHits() == null ? 0 : statistics.getTotalHits(),
                System.currentTimeMillis()
        );
    }

    public boolean valid() {
        return userId > 0 && globalRank > 0 && Double.isFinite(pp) && pp > 0;
    }
}
