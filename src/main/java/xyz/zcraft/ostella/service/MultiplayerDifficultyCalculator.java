package xyz.zcraft.ostella.service;

import com.google.gson.Gson;
import desu.life.RosuFFI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.zcraft.osu.model.BeatmapExtended;
import xyz.zcraft.osu.model.Score;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Request-local cache: never mutate the shared beatmap model with a player's stars.
 */
final class MultiplayerDifficultyCalculator {
    private static final Logger LOG = LoggerFactory.getLogger(MultiplayerDifficultyCalculator.class);
    private static final Gson GSON = new Gson();
    private final boolean lazer;
    private final Map<Key, Optional<Double>> ratings = new HashMap<>();

    MultiplayerDifficultyCalculator(String client) {
        lazer = !"stable".equalsIgnoreCase(client);
    }

    static double calculate(Path path, String ruleset, String json, boolean lazer) {
        RosuFFI.Mode mode = switch (ruleset) {
            case "0", "osu" -> RosuFFI.Mode.Osu;
            case "1", "taiko" -> RosuFFI.Mode.Taiko;
            case "2", "fruits", "catch" -> RosuFFI.Mode.Catch;
            case "3", "mania" -> RosuFFI.Mode.Mania;
            default -> throw new IllegalArgumentException("Unknown ruleset: " + ruleset);
        };
        try (var map = new RosuFFI.Beatmap(path.toAbsolutePath().toString());
             var mods = RosuFFI.Mods.fromJson(json, mode, false);
             var difficulty = new RosuFFI.Difficulty()) {
            if (map.mode() != mode && !map.convert(mode, mods)) {
                throw new IllegalArgumentException("Cannot convert beatmap to " + mode);
            }
            difficulty.mods(mods);
            difficulty.lazer(lazer);
            var attributes = difficulty.calculate(map);
            double stars = switch (mode) {
                case Osu -> attributes.asOsu().stars;
                case Taiko -> attributes.asTaiko().stars;
                case Catch -> attributes.asCatch().stars;
                case Mania -> attributes.asMania().stars;
            };
            if (!Double.isFinite(stars) || stars < 0) throw new IllegalStateException("Invalid stars");
            return stars;
        }
    }

    Double rating(Score score, BeatmapExtended map, long fallbackId) {
        long id = map != null && map.getId() != null ? map.getId()
                : score.getBeatmapId() != null ? score.getBeatmapId() : fallbackId;
        String mode = score.getRulesetId() != null ? score.getRulesetId().toString()
                : map == null || map.getMode() == null ? "osu" : map.getMode();
        var mods = MultiplayerResultFactory.validMods(score.getMods());
        String json = GSON.toJson(mods.stream()
                .sorted(java.util.Comparator.comparing(mod -> mod.getAcronym())).toList());
        Key key = new Key(id, mode, json);
        return ratings.computeIfAbsent(key, ignored -> {
            try {
                return Optional.of(calculate(CacheService.getBeatmapPath(id), mode, json, lazer));
            } catch (Exception e) {
                LOG.warn("Failed to calculate multiplayer difficulty for beatmap {} with {}", id, json, e);
                return Optional.empty();
            }
        }).orElse(null);
    }

    private record Key(long beatmapId, String ruleset, String mods) {
    }
}
