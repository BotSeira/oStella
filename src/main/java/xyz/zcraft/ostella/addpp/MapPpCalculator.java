package xyz.zcraft.ostella.addpp;

import desu.life.RosuFFI;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public final class MapPpCalculator {
    private MapPpCalculator() {
    }

    public static Result calculate(Path path, MapConditions conditions) {
        String acronyms = conditions.mods();
        Set<String> single = new HashSet<>();
        boolean isLazer = true;
        for (int i = 0; i < acronyms.length() / 2; i++) {
            final String mod = acronyms.substring(i * 2, i * 2 + 2);
            if ("CL".equals(mod)) {
                isLazer = false;
            } else {
                single.add(mod);
            }
        }

        try (var map = new RosuFFI.Beatmap(path.toAbsolutePath().toString());
             var mods = RosuFFI.Mods.fromAcronyms(String.join("", single), RosuFFI.Mode.Osu);
             var difficulty = new RosuFFI.Difficulty();
             var performance = new RosuFFI.Performance()) {
            if (map.mode() != RosuFFI.Mode.Osu) throw new IllegalArgumentException("仅支持 osu!standard 原生谱面。");
            difficulty.mods(mods);
            difficulty.lazer(isLazer);
            var attributes = difficulty.calculate(map);
            var osu = attributes.asOsu();
            var hits = conditions.resolve(osu.n_circles + osu.n_sliders + osu.n_spinners, osu.max_combo);
            performance.mods(mods);
            performance.lazer(isLazer);
            performance.n300(hits.great());
            performance.n100(hits.ok());
            performance.n50(hits.meh());
            performance.misses(hits.misses());
            performance.combo(hits.combo());
            double pp = performance.calculate(attributes).asOsu().pp;
            if (!Double.isFinite(pp) || pp < 0) throw new IllegalStateException("谱面 PP 计算失败。");
            return new Result(pp, osu.stars, osu.max_combo, hits);
        }
    }

    public record Result(double pp, double stars, int maxCombo, MapConditions.Resolved hits) {
    }
}
