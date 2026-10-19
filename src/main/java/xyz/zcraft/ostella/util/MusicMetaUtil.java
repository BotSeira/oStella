package xyz.zcraft.ostella.util;

import java.text.Normalizer;
import java.util.Locale;

public final class MusicMetaUtil {
    private MusicMetaUtil() {
    }

    public static String normalize(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        String result = Normalizer.normalize(
                input, Normalizer.Form.NFKC
        );

        return result
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{S}]+", " ")
                .replaceAll("[\\p{Z}\\s]+", " ")
                .trim();
    }

    public static String baseTitle(String input) {
        String result = input;
        result = result.replaceFirst("\\(.*\\)$", "").trim();
        result = normalize(result);
        result = result.replaceFirst("(tv size|tv edit|short ver|short version)$", "");
        return result.trim();
    }

    public static String normalizeOrFallback(String preferred, String fallback) {
        String normalized = normalize(preferred);
        return normalized.isEmpty() ? normalize(fallback) : normalized;
    }
}
