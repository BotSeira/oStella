package xyz.zcraft.ostella.data;

import xyz.zcraft.ostella.util.Beatmapsets;
import xyz.zcraft.osu.model.Beatmapset;

public record SearchResultItem(
        long beatmapsetId,
        String artist,
        String title,
        String mapperName,
        double minStar,
        double maxStar,
        String coverUrl
) {
    public static SearchResultItem fromBeatmapset(Beatmapset ms) {
        return new SearchResultItem(
                ms.getId(), ms.getArtist(), ms.getTitle(), ms.getCreator(),
                Beatmapsets.getMinStar(ms), Beatmapsets.getMaxStar(ms), ms.getCovers().getSlimcover()
        );
    }
}
