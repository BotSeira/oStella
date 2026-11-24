package xyz.zcraft.ostella.data;

import xyz.zcraft.osu.model.Beatmap;
import xyz.zcraft.osu.model.Beatmapset;
import xyz.zcraft.osu.model.Mod;
import xyz.zcraft.osu.model.Score;

import java.util.List;

public record ReworkedScore(
        Beatmap beatmap,
        Beatmapset beatmapset,
        long id,
        String rank,
        int combo,
        double accuracy,
        int countMiss,
        int count100,
        int count50,
        int count300,
        List<String> modsString,
        double liveSr,
        double livePp,
        double localSr,
        double localPp,
        Score.Weight weight,
        String endedAt,
        PerformanceBreakdown pp,
        int posChange
) {
    public List<Mod> getMods() {
        return modsString.stream().map(s -> {
            final Mod mod = new Mod();
            mod.setAcronym(s);
            return mod;
        }).toList();
    }
}
