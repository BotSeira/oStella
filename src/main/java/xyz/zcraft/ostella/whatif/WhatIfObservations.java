package xyz.zcraft.ostella.whatif;

import com.google.gson.JsonObject;



/**
 * Optional query metadata must never cause the original query to fail.
 */
public final class WhatIfObservations {
    private WhatIfObservations() {
    }

    public static RankPpModel.Sample fromUser(JsonObject user, boolean standardStatistics) {
        try {
            JsonObject stats = null;
            if (user.has("statistics_rulesets") && user.get("statistics_rulesets").isJsonObject()) {
                var rulesets = user.getAsJsonObject("statistics_rulesets");
                if (rulesets.has("osu") && rulesets.get("osu").isJsonObject()) stats = rulesets.getAsJsonObject("osu");
            }
            // Default-mode endpoints expose statistics for the user's preferred playmode.
            boolean standardDefault = user.has("playmode") && !user.get("playmode").isJsonNull()
                    && "osu".equals(user.get("playmode").getAsString());
            if (stats == null && (standardStatistics || standardDefault)) stats = user.getAsJsonObject("statistics");
            if (stats == null) return null;
            var sample = new RankPpModel.Sample(user.get("id").getAsLong(),
                    stats.get("global_rank").getAsLong(), stats.get("pp").getAsDouble());
            return sample.valid() ? sample : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

}
