package xyz.zcraft.ostella.data;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import xyz.zcraft.osu.model.BeatmapExtended;
import xyz.zcraft.osu.model.Beatmapset;

/** Builds an independent response model without a beatmap -> set -> beatmap cycle. */
public final class BeatmapData {
    private static final Gson GSON = new Gson();

    private BeatmapData() {
    }

    public static BeatmapExtended withBeatmapset(BeatmapExtended beatmap, Beatmapset beatmapset) {
        BeatmapExtended result = GSON.fromJson(GSON.toJsonTree(beatmap), BeatmapExtended.class);
        JsonObject setData = GSON.toJsonTree(beatmapset).getAsJsonObject();
        setData.remove("beatmaps");
        result.setBeatmapset(GSON.fromJson(setData, Beatmapset.class));
        return result;
    }
}
