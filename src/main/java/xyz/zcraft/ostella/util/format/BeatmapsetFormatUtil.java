package xyz.zcraft.ostella.util.format;

import xyz.zcraft.osu.model.Beatmap;
import xyz.zcraft.osu.model.BeatmapExtended;
import xyz.zcraft.osu.model.Beatmapset;

import java.util.Objects;
import java.util.Optional;

public class BeatmapsetFormatUtil {
    public static boolean hasTitleUnicode(Beatmapset beatmapset) {
        if (beatmapset == null) return false;
        return !Objects.equals(beatmapset.getTitle(), beatmapset.getTitleUnicode());
    }

    public static boolean hasArtistUnicode(Beatmapset beatmapset) {
        if (beatmapset == null) return false;
        return !Objects.equals(beatmapset.getArtist(), beatmapset.getArtistUnicode());
    }

    public static String getTagName(Beatmapset beatmapset, int id) {
        if (beatmapset == null || beatmapset.getRelatedTags() == null) {
            return null;
        }

        return beatmapset.getRelatedTags().stream()
                .filter(tag -> Objects.equals(tag.getId(), id))
                .findFirst()
                .map(Beatmapset.UserTag::getName)
                .orElse(null);
    }

    public static String getRatingString(Beatmapset beatmapset) {
        return Optional.ofNullable(beatmapset)
                .map(Beatmapset::getRating)
                .map(r -> String.format("%.2f", r))
                .orElse("--");
    }

    public static String getGenreString(Beatmapset beatmapset) {
        return Optional.ofNullable(beatmapset)
                .map(Beatmapset::getGenre)
                .map(Beatmapset.Label::getName)
                .filter(s -> !s.isBlank())
                .orElse("--");
    }

    public static String getSourceString(Beatmapset beatmapset) {
        return Optional.ofNullable(beatmapset)
                .map(Beatmapset::getSource)
                .filter(s -> !s.isBlank())
                .orElse("--");
    }

    public static String getLanguageString(Beatmapset beatmapset) {
        return Optional.ofNullable(beatmapset)
                .map(Beatmapset::getLanguage)
                .map(Beatmapset.Label::getName)
                .filter(s -> !s.isBlank())
                .orElse("--");
    }

    public static double getMaxStar(Beatmapset beatmapset) {
        if (beatmapset == null || beatmapset.getBeatmaps() == null || beatmapset.getBeatmaps().isEmpty()) {
            return 0;
        }

        return beatmapset.getBeatmaps().stream()
                .mapToDouble(BeatmapExtended::getDifficultyRating)
                .max()
                .orElse(0);
    }

    public static double getMinStar(Beatmapset beatmapset) {
        if (beatmapset == null || beatmapset.getBeatmaps() == null || beatmapset.getBeatmaps().isEmpty()) {
            return 0;
        }

        return beatmapset.getBeatmaps().stream()
                .mapToDouble(BeatmapExtended::getDifficultyRating)
                .min()
                .orElse(0);
    }

    public static boolean shouldWarpSubtitle(Beatmapset set) {
        if (!hasTitleUnicode(set)) {
            return false;
        }

        final boolean subArtistPresent = hasArtistUnicode(set);

        if (!subArtistPresent) {
            return set.getTitle().length() + set.getTitleUnicode().length() > 40;
        } else {
            return set.getTitleUnicode().length() + set.getArtist().length() + set.getArtistUnicode().length() < 50;
        }
    }
}
