package xyz.zcraft.ostella.addpp;

import desu.life.RosuFFI;
import xyz.zcraft.osu.model.ModSettings;

import java.nio.file.Path;

public final class MapPpCalculator {
    private MapPpCalculator() {
    }

    public static Result calculate(Path path, MapConditions conditions) {
        var parsedMods = ModSettings.parse(conditions.mods());
        boolean isLazer = parsedMods.stream().noneMatch(mod -> "CL".equals(mod.getAcronym())
                && (mod.getSettings() == null || !Boolean.FALSE.equals(mod.getSettings().get("no_slider_head_accuracy"))));

        try (var map = new RosuFFI.Beatmap(path.toAbsolutePath().toString());
             var mods = xyz.zcraft.osu.parser.OsuParser.toRosuMods(parsedMods);
             var difficulty = new RosuFFI.Difficulty();
             var performance = new RosuFFI.Performance()) {
            if (map.mode() != RosuFFI.Mode.Osu) throw new IllegalArgumentException("仅支持 osu!standard 原生谱面。");
            difficulty.mods(mods);
            difficulty.lazer(isLazer);
            var attributes = difficulty.calculate(map);
            var osu = attributes.asOsu();
            conditions.validate(osu.n_circles + osu.n_sliders + osu.n_spinners, osu.max_combo);
            performance.mods(mods);
            performance.lazer(isLazer);
            if (conditions.accuracy() != null) performance.accuracy(conditions.accuracy());
            if (conditions.great() != null) performance.n300(conditions.great());
            if (conditions.ok() != null) performance.n100(conditions.ok());
            if (conditions.meh() != null) performance.n50(conditions.meh());
            if (conditions.misses() != null) performance.misses(conditions.misses());
            if (conditions.combo() != null) performance.combo(conditions.combo());
            // FC is an explicit shorthand for zero misses and full combo.
            if (conditions.fc()) {
                performance.misses(0);
                performance.combo(osu.max_combo);
            }
            double pp = performance.calculate(attributes).asOsu().pp;
            if (!Double.isFinite(pp) || pp < 0) throw new IllegalStateException("谱面 PP 计算失败。");
            var state = performance.generateState(attributes);
            double accuracy = 100 * RosuFFI.calculateAccuracy(state, attributes,
                    isLazer ? RosuFFI.OsuScoreOrigin.WithSliderAcc : RosuFFI.OsuScoreOrigin.Stable);
            var hits = new MapConditions.Resolved(state.n300, state.n100, state.n50, state.misses,
                    state.max_combo, accuracy);
            return new Result(pp, osu.stars, osu.max_combo, hits);
        }
    }

    public record Result(double pp, double stars, int maxCombo, MapConditions.Resolved hits) {
    }
}
