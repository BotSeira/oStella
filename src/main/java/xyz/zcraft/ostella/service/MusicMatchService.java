package xyz.zcraft.ostella.service;

import xyz.zcraft.ostella.db.SetMetaStore;
import xyz.zcraft.ostella.util.MusicMetaUtil;

import java.util.*;

public final class MusicMatchService {
    public static final int CANDIDATE_LIMIT = 500;
    public static final int RESULT_LIMIT = 50;

    public static Result match(String title, String artist) {
        return match(title, artist, SetMetaStore.candidates(title, artist, CANDIDATE_LIMIT + 1));
    }

    public static Result match(String title, String artist, List<SetMetaStore.Metadata> candidates) {
        String titleKey = MusicMetaUtil.normalize(title);
        String artistKey = MusicMetaUtil.normalize(artist);

        if (titleKey.isEmpty()) throw new IllegalArgumentException("Title must contain letters or numbers");

        boolean truncated = candidates.size() > CANDIDATE_LIMIT;

        List<Match> matches = new ArrayList<>();

        for (var candidate : candidates.stream().limit(CANDIDATE_LIMIT).toList()) {
            if (!"ranked".equals(candidate.status())) continue;

            Set<String> titles = keys(candidate.title(), candidate.titleUnicode());

            boolean exactTitle = titles.contains(titleKey);
            boolean baseTitle = titles.stream()
                    .anyMatch(t -> MusicMetaUtil.baseTitle(t).equals(MusicMetaUtil.baseTitle(titleKey)));

            if (!exactTitle && !baseTitle) continue;

            Set<String> artists = keys(candidate.artist(), candidate.artistUnicode());

            boolean exactArtist = !artistKey.isEmpty() && artists.contains(artistKey);
            double artistSimilarity = artistKey.isEmpty() ? 0 : artists.stream()
                    .mapToDouble(a -> similarity(artistKey, a)).max().orElse(0);

            if (!artistKey.isEmpty() && !exactArtist && artistSimilarity < 0.85) continue;

            boolean exact = exactTitle && exactArtist;
            String reason = exact ? "exact_title_and_artist"
                    : !exactTitle ? "length_variant_requires_review"
                    : artistKey.isEmpty() ? "title_only_requires_review" : "similar_artist_requires_review";

            double score = exact ? 1.0 : (exactTitle ? 0.7 : 0.5) + 0.2 * artistSimilarity;

            matches.add(new Match(candidate, score, reason, exact));
        }

        matches.sort(Comparator.comparingDouble(Match::score).reversed()
                .thenComparing(Comparator.comparingLong((Match m) -> m.beatmapset().favouriteCount()).reversed())
                .thenComparing(Comparator.comparingLong((Match m) -> m.beatmapset().playCount()).reversed())
                .thenComparingLong(m -> m.beatmapset().id()));

        String status = matches.isEmpty() ? "no_match" : matches.stream().anyMatch(Match::exact) ? "matched" : "ambiguous";

        return new Result(
                title, artist, status, matches.stream().limit(RESULT_LIMIT).toList(),
                truncated || matches.size() > RESULT_LIMIT
        );
    }

    private static Set<String> keys(String primary, String unicode) {
        Set<String> keys = new HashSet<>(List.of(MusicMetaUtil.normalize(primary), MusicMetaUtil.normalize(unicode)));
        keys.remove("");
        return keys;
    }

    private static double similarity(String left, String right) {
        if (left.equals(right)) return 1;

        final int max = Math.max(left.length(), right.length());
        if (Math.min(left.length(), right.length()) < 5 || max > 256) return 0;
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j < previous.length; j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
            }
            previous = current;
        }
        return 1.0 - (double) previous[right.length()] / max;
    }

    public record Match(SetMetaStore.Metadata beatmapset, double score, String reason, boolean exact) {
    }

    public record Result(String title, String artist, String status, List<Match> matches, boolean truncated) {
    }
}
