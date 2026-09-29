package xyz.zcraft.ostella.network;

import com.google.gson.JsonObject;
import lombok.Getter;

@Getter
public enum ErrorCode {
    NO_BEATMAP_FOUND(1001),
    NO_BEATMAPSET_FOUND(1002),
    NO_USER_FOUND(1003),
    NO_SCORE_FOUND(1004),
    NO_ROOM_FOUND(1005),
    NO_BACKGROUND_FOUND(1006),

    ILLEGAL_ARGUMENT(2001),
    UNAUTHORIZED(2002),

    FETCH_FAILED(3000),
    BEATMAP_FETCH_FAILED(3001),
    BEATMAPSET_FETCH_FAILED(3002),
    USER_FETCH_FAILED(3003),
    SCORE_FETCH_FAILED(3004),
    ROOM_FETCH_FAILED(3005),
    IMAGE_FETCH_FAILED(3006),
    REPLAY_FETCH_FAILED(3007),
    TOKEN_FETCH_FAILED(3008),

    REPLAY_UNAVAILABLE(4001),
    BEATMAP_PARSE_FAILED(4002),
    SCORE_PARSE_FAILED(4003),
    REPLAY_PARSE_FAILED(4004),
    REPLAY_UPLOAD_FAILED(4005),

    RENDER_QUEUE_FULL(5001),
    RENDERER_UNAVAILABLE(5002),
    PERFORMANCE_PLUS_UNAVAILABLE(5003),
    IMAGE_RENDER_TIMEOUT(5004);

    private final int code;

    ErrorCode(int i) {
        this.code = i;
    }

    public JsonObject toJson() {
        final JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("code", code);
        jsonObject.addProperty("httpStatus", getHttpCode());
        return jsonObject;
    }

    public int getHttpCode() {
        return switch (this) {
            case ErrorCode.NO_BEATMAP_FOUND,
                 ErrorCode.NO_BEATMAPSET_FOUND,
                 ErrorCode.NO_SCORE_FOUND,
                 ErrorCode.NO_ROOM_FOUND,
                 ErrorCode.NO_USER_FOUND -> 404;

            case ErrorCode.UNAUTHORIZED -> 401;

            case ErrorCode.ILLEGAL_ARGUMENT,
                 ErrorCode.REPLAY_UNAVAILABLE -> 400;

            case ErrorCode.BEATMAP_FETCH_FAILED,
                 ErrorCode.BEATMAPSET_FETCH_FAILED,
                 ErrorCode.SCORE_FETCH_FAILED,
                 ErrorCode.USER_FETCH_FAILED,
                 ErrorCode.RENDER_QUEUE_FULL -> 429;

            case ErrorCode.RENDERER_UNAVAILABLE,
                 ErrorCode.PERFORMANCE_PLUS_UNAVAILABLE -> 502;

            default -> 500;
        };
    }
}
