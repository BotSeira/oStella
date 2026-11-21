package xyz.zcraft.ostella.network;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.javalin.http.Context;
import xyz.zcraft.ostella.exception.ApiException;
import xyz.zcraft.osu.model.Beatmap;
import xyz.zcraft.osu.model.BeatmapExtended;
import xyz.zcraft.osu.model.Beatmapset;

import java.util.Map;
import java.util.Set;

public enum ResponseVerbosity {
    COMPACT, STANDARD, FULL;

    private static final Map<Class<?>, Set<String>> COMPACT_FIELDS = Map.of(
            Beatmapset.class, Set.of("id", "title", "artist", "creator", "status", "beatmaps"),
            Beatmap.class, Set.of("id", "beatmapsetId", "version", "mode", "status", "difficultyRating"),
            BeatmapExtended.class, Set.of("beatmapset")
    );
    private static final Map<Class<?>, Set<String>> STANDARD_EXTRA_FIELDS = Map.of(
            Beatmapset.class, Set.of("titleUnicode", "artistUnicode", "bpm", "source", "userId",
                    "tags", "rankedDate", "playCount", "favouriteCount"),
            Beatmap.class, Set.of("totalLength", "userId"),
            BeatmapExtended.class, Set.of("ar", "accuracy", "cs", "drain", "bpm", "hitLength", "maxCombo",
                    "countCircles", "countSliders", "countSpinners")
    );
    private static final Gson COMPACT_GSON = createGson(false);
    private static final Gson STANDARD_GSON = createGson(true);

    public static ResponseVerbosity forJson(Context context) {
        if (!ImageResponse.acceptsJson(context.header("Accept"))) return FULL;
        return parse(context.queryParam("verbosity"));
    }

    static ResponseVerbosity parse(String value) {
        if (value == null) return FULL;
        return switch (value) {
            case "compact" -> COMPACT;
            case "standard" -> STANDARD;
            case "full" -> FULL;
            default -> throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT,
                    "verbosity must be compact, standard, or full");
        };
    }

    private static Gson createGson(boolean standard) {
        return new GsonBuilder().serializeSpecialFloatingPointValues()
                .addSerializationExclusionStrategy(new ExclusionStrategy() {
                    @Override
                    public boolean shouldSkipField(FieldAttributes field) {
                        Class<?> owner = field.getDeclaringClass();
                        Set<String> fields = COMPACT_FIELDS.get(owner);
                        return fields != null && !fields.contains(field.getName())
                                && !(standard && STANDARD_EXTRA_FIELDS.get(owner).contains(field.getName()));
                    }

                    @Override
                    public boolean shouldSkipClass(Class<?> type) {
                        return false;
                    }
                }).create();
    }

    public Object project(Object data) {
        return switch (this) {
            case COMPACT -> COMPACT_GSON.toJsonTree(data);
            case STANDARD -> STANDARD_GSON.toJsonTree(data);
            case FULL -> data;
        };
    }
}
