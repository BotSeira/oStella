package xyz.zcraft.ostella.util;

import io.javalin.http.Context;
import xyz.zcraft.ostella.exception.ApiException;
import xyz.zcraft.ostella.network.ErrorCode;
import xyz.zcraft.ostella.network.OsuAPI;

public record ScoreListRange(int start, int end) {
    public ScoreListRange {
        if (start < 1 || end < start || end > OsuAPI.MAX_USER_SCORES_LIMIT)
            throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT,
                    "Score range must satisfy 1 <= start <= end <= " + OsuAPI.MAX_USER_SCORES_LIMIT);
    }

    public static ScoreListRange from(Context context) {
        return parse(context.queryParam("start"), context.queryParam("end"), context.queryParam("n"));
    }

    public static ScoreListRange parse(String start, String end, String n) {
        if (n != null ? end != null : start == null || end == null)
            throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT,
                    "Provide n (with optional start), or both start and end; do not mix n and end");
        try {
            if (n != null) {
                int count = Integer.parseInt(n);
                if (count < 1 || count > OsuAPI.MAX_USER_SCORES_LIMIT)
                    throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT,
                            "Score count must be between 1 and " + OsuAPI.MAX_USER_SCORES_LIMIT);
                int first = start == null ? 1 : Integer.parseInt(start);
                return new ScoreListRange(first, Math.addExact(first, count - 1));
            }
            return new ScoreListRange(Integer.parseInt(start), Integer.parseInt(end));
        } catch (NumberFormatException | ArithmeticException e) {
            throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, "Score positions must be positive integers");
        }
    }

    public int count() {
        return end - start + 1;
    }

    public int offset() {
        return start - 1;
    }
}
