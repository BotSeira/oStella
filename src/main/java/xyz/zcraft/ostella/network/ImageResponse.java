package xyz.zcraft.ostella.network;

import io.javalin.http.Context;
import xyz.zcraft.ostella.util.RequestUtil;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/** Content negotiation shared by endpoints offering data and rendered images. */
public final class ImageResponse {
    private ImageResponse() {
    }

    public static boolean wantsJson(Context context) {
        String vary = context.res().getHeader("Vary");
        if (vary == null || vary.isBlank()) {
            context.header("Vary", "Accept");
        } else if (Arrays.stream(vary.split(",")).map(String::trim)
                .noneMatch(value -> value.equalsIgnoreCase("Accept") || value.equals("*"))) {
            context.header("Vary", vary + ", Accept");
        }
        return acceptsJson(context.header("Accept"));
    }

    // Explicit JSON opt-in preserves image responses for browsers and wildcard clients.
    static boolean acceptsJson(String accept) {
        if (accept == null) return false;
        for (String range : accept.split(",")) {
            String[] parts = range.trim().split(";");
            if (parts.length == 0 || !parts[0].trim().equalsIgnoreCase("application/json")) continue;
            double quality = 1;
            for (int i = 1; i < parts.length; i++) {
                String[] parameter = parts[i].trim().split("=", 2);
                if (!parameter[0].trim().equalsIgnoreCase("q")) continue;
                try {
                    quality = parameter.length == 2 ? Double.parseDouble(parameter[1].trim()) : 0;
                } catch (NumberFormatException ignored) {
                    quality = 0;
                }
            }
            if (quality > 0 && quality <= 1) return true;
        }
        return false;
    }

    public static <T> CompletableFuture<Void> respond(
            Context context, T data, Function<T, byte[]> render, Executor renderExecutor) {
        return respond(context, data, Function.identity(), render, renderExecutor);
    }

    public static <T> CompletableFuture<Void> respond(
            Context context, T data, Function<T, ?> jsonData,
            Function<T, byte[]> render, Executor renderExecutor) {
        if (wantsJson(context)) {
            RequestUtil.putResult(context, jsonData.apply(data));
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> render.apply(data), renderExecutor)
                .thenAccept(bytes -> context.status(200).contentType("image/png").result(bytes));
    }
}
