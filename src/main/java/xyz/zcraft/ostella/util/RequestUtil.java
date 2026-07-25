package xyz.zcraft.ostella.util;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import io.javalin.http.Context;
import xyz.zcraft.ostella.network.ApiException;
import xyz.zcraft.ostella.network.ErrorCode;
import xyz.zcraft.ostella.network.Response;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

public class RequestUtil {
    private final static Gson GSON = new Gson();

    public static void putResult(Context context, Object result) {
        context.status(200);
        JsonElement data;

        if (result instanceof JsonElement) {
            data = (JsonElement) result;
        } else {
            data = GSON.toJsonTree(result);
        }

        context.result(new Response(true, "Success", data).toString());
    }

    public static void putResult(Context context, String key, String val) {
        putResult(context, Map.of(key, val));
    }

    public static int requireInt(Context context, String param) throws ApiException {
        try {
            return Integer.parseInt(Objects.requireNonNull(context.queryParam(param)));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, "Invalid parameter: " + param);
        }
    }

    public static int requirePositiveInt(Context context, String param) throws ApiException {
        try {
            final int i = Integer.parseInt(Objects.requireNonNull(context.queryParam(param)));
            if (i <= 0) throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, "Parameter must be positive: " + param);
            return i;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, "Invalid parameter: " + param);
        }
    }

    public static double optionalDouble(Context context, String param) throws ApiException {
        try {
            final String obj = context.queryParam(param);
            if (obj == null) return Double.NaN;
            return Double.parseDouble(obj);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, "Invalid parameter: " + param);
        }
    }

    public static String requireString(Context context, String param) throws ApiException {
        final String s = context.queryParam(param);
        if (s != null && !s.isBlank()) {
            return s;
        } else {
            throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, "Missing or empty parameter: " + param);
        }
    }

    public static String optionalString(Context context, String param) throws ApiException {
        return context.queryParam(param);
    }

    public static long requireLong(Context context, String param) throws ApiException {
        try {
            return Long.parseLong(Objects.requireNonNull(context.queryParam(param)));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, "Invalid parameter: " + param);
        }
    }

    public static boolean requireBoolean(Context context, String param, boolean fallback) throws ApiException {
        try {
            final String obj = context.queryParam(param);
            if (obj == null || obj.isBlank()) return fallback;

            if ("true".equalsIgnoreCase(obj)) return true;
            if ("false".equalsIgnoreCase(obj)) return false;
            return fallback;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, "Invalid parameter: " + param);
        }
    }

    public static long requirePathLong(Context context, String param) throws ApiException {
        try {
            final String s = context.pathParam(param);
            return Long.parseLong(Objects.requireNonNull(s));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, "Invalid path parameter: " + param);
        }
    }

    public static int requirePathInt(Context context, String param) throws ApiException {
        try {
            final String s = context.pathParam(param);
            return Integer.parseInt(Objects.requireNonNull(s));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, "Invalid path parameter: " + param);
        }
    }

    public static String requireStringFrom(Context context, String param, String... values) throws ApiException {
        final String s = context.queryParam(param);

        if (s != null && !s.isBlank()) {
            if (Arrays.asList(values).contains(s)) {
                return s;
            } else {
                throw new ApiException(
                        ErrorCode.ILLEGAL_ARGUMENT,
                        "Invalid parameter: " + param + ", should be one of " + Arrays.toString(values)
                );
            }
        }

        throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, "Missing or empty parameter: " + param);
    }
}
