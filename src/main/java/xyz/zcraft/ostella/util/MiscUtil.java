package xyz.zcraft.ostella.util;

import com.google.gson.JsonObject;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public class MiscUtil {
    public static String getRelativeTimeAgo(String isoTimestamp) {
        Instant pastTime = Instant.parse(isoTimestamp);
        Instant now = Instant.now();

        Duration duration = Duration.between(pastTime, now);

        long days = duration.toDays();
        if (days > 0) {
            return days + (days == 1 ? " dy ago" : " dys ago");
        }

        long hours = duration.toHours();
        if (hours > 0) {
            return hours + (hours == 1 ? " hr ago" : " hrs ago");
        }

        long minutes = duration.toMinutes();
        if (minutes > 0) {
            return minutes + (minutes == 1 ? " min ago" : " mins ago");
        }

        long seconds = duration.getSeconds();
        if (seconds < 5) {
            return "just now";
        }
        return seconds + " secs ago";
    }

    public static JsonObject deepMergeJson(JsonObject first, JsonObject... others) {
        JsonObject merged = first.deepCopy();
        for (JsonObject other : others) {
            for (String key : other.keySet()) {
                if (merged.has(key)) {
                    if (merged.get(key).isJsonObject() && other.get(key).isJsonObject()) {
                        merged.add(key, deepMergeJson(merged.getAsJsonObject(key), other.getAsJsonObject(key)));
                    } else {
                        merged.add(key, other.get(key));
                    }
                } else {
                    merged.add(key, other.get(key));
                }
            }
        }
        return merged;
    }

    public static boolean strEquals(String a, String b) {
        return Objects.equals(a, b);
    }
}
