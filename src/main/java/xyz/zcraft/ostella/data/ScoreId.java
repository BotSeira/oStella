package xyz.zcraft.ostella.data;

import xyz.zcraft.osu.model.Score;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ScoreId {
    private static final Pattern LOCAL_ID = Pattern.compile("(?i)^loc([1-9]\\d*)$");

    private ScoreId() {
    }

    public static long parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Score ID is missing");
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        Matcher local = LOCAL_ID.matcher(normalized);
        if (local.matches()) {
            long id = parsePositive(local.group(1));
            return -id;
        }

        return parsePositive(normalized);
    }

    public static String format(long id) {
        if (id == Long.MIN_VALUE || id == 0) {
            throw new IllegalArgumentException("Invalid score ID: " + id);
        }
        return id < 0 ? "loc" + -id : String.valueOf(id);
    }

    public static String format(Score score) {
        if (score == null || score.getId() == null) {
            throw new IllegalArgumentException("Score has no ID");
        }
        return format(score.getId());
    }

    public static boolean isLocal(long id) {
        return id < 0;
    }

    public static boolean isLocal(Score score) {
        return score != null && score.getId() != null && isLocal(score.getId());
    }

    public static long renderAssetId(long id) {
        if (id == Long.MIN_VALUE || id == 0) {
            throw new IllegalArgumentException("Invalid score ID: " + id);
        }
        return Math.abs(id);
    }

    private static long parsePositive(String value) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0) {
                throw new IllegalArgumentException("Score ID must be positive");
            }
            return id;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid score ID: " + value, e);
        }
    }
}
