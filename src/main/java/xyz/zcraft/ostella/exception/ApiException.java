package xyz.zcraft.ostella.exception;

import lombok.Getter;
import xyz.zcraft.ostella.network.ErrorCode;

@Getter
public class ApiException extends RuntimeException {
    private final ErrorCode errorCode;
    private final Exception wrappedException;

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.wrappedException = null;
    }

    public ApiException(ErrorCode errorCode, String message, Exception wrappedException) {
        super(message);
        this.errorCode = errorCode;
        this.wrappedException = wrappedException;
    }

    public ApiException(ErrorCode errorCode) {
        super(getDefaultMessage(errorCode));
        this.errorCode = errorCode;
        this.wrappedException = null;
    }

    public ApiException(ErrorCode errorCode, Exception wrappedException) {
        super(getDefaultMessage(errorCode));
        this.errorCode = errorCode;
        this.wrappedException = wrappedException;
    }

    private static String getDefaultMessage(ErrorCode errorCode) {
        return switch (errorCode) {
            case NO_BEATMAP_FOUND -> "No beatmap found";
            case NO_BEATMAPSET_FOUND -> "No beatmapset found";
            case NO_USER_FOUND -> "No user found";
            case NO_SCORE_FOUND -> "No score found";
            case NO_ROOM_FOUND -> "No rooms found";
            case ILLEGAL_ARGUMENT -> "Illegal argument";
            case UNAUTHORIZED -> "Unauthorized";
            case FETCH_FAILED -> "Fetch failed";
            case BEATMAP_FETCH_FAILED -> "Beatmap fetch failed";
            case BEATMAPSET_FETCH_FAILED -> "Beatmapset fetch failed";
            case USER_FETCH_FAILED -> "User fetch failed";
            case SCORE_FETCH_FAILED -> "Score fetch failed";
            case REPLAY_UNAVAILABLE -> "Replay unavailable";
            case BEATMAP_PARSE_FAILED -> "Beatmap parse failed";
            case SCORE_PARSE_FAILED -> "Score parse failed";
            case REPLAY_PARSE_FAILED -> "Replay parse failed";
            case RENDER_QUEUE_FULL -> "Render queue full";
            case REPLAY_FETCH_FAILED -> "Replay fetch failed";
            case ROOM_FETCH_FAILED -> "Room fetch failed";
            case IMAGE_FETCH_FAILED -> "Image fetch failed";
            case TOKEN_FETCH_FAILED -> "Token fetch failed";
            case REPLAY_UPLOAD_FAILED -> "Replay upload failed";
            case null -> "Unknown error";
        };
    }
}
