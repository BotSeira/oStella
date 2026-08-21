package xyz.zcraft.ostella.service;

import xyz.zcraft.osu.parser.BeatmapAnalyzer;
import xyz.zcraft.osu.parser.data.beatmap.OsuBeatmap;
import xyz.zcraft.osu.parser.data.beatmap.WindowDifficulty;
import xyz.zcraft.osu.parser.exception.AnalyzeException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class BeatmapPreviewService {
    static final double OUTPUT_DURATION_SECONDS = 30.0;
    private static final int MAX_DIFFICULTY_CANDIDATES = 120;
    private static final Set<String> SUPPORTED_MODS = Set.of(
            "NF", "EZ", "HD", "HR", "SD", "DT", "HT", "NC", "FL", "SO", "PF", "MR"
    );

    private BeatmapPreviewService() {
    }

    public static String normalizeMods(String value) {
        if (value == null || value.isBlank() || "NM".equalsIgnoreCase(value.trim())) {
            return "";
        }

        String compact = value.trim().toUpperCase(Locale.ROOT);
        if (compact.startsWith("+")) {
            compact = compact.substring(1);
        }
        if (compact.isEmpty() || compact.length() % 2 != 0 || !compact.matches("[A-Z0-9]+")) {
            throw new IllegalArgumentException("Mod must be a sequence of two-character acronyms");
        }

        List<String> acronyms = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < compact.length(); i += 2) {
            String acronym = compact.substring(i, i + 2);
            if (!SUPPORTED_MODS.contains(acronym)) {
                throw new IllegalArgumentException("Unsupported Mod: " + acronym);
            }
            if (!seen.add(acronym)) {
                throw new IllegalArgumentException("Duplicate Mod: " + acronym);
            }
            acronyms.add(acronym);
        }

        requireAtMostOne(seen, "EZ", "HR");
        requireAtMostOne(seen, "DT", "NC", "HT");
        requireAtMostOne(seen, "SD", "PF");
        return String.join("", acronyms);
    }

    public static PreviewSegment selectSegment(OsuBeatmap beatmap, String mods) throws AnalyzeException {
        if (beatmap == null || beatmap.getHitObjects() == null || beatmap.getHitObjects().isEmpty()) {
            throw new IllegalArgumentException("Beatmap contains no hit objects");
        }
        if (beatmap.getMode() != null && beatmap.getMode() != 0) {
            throw new IllegalArgumentException("Only osu!standard beatmaps can be previewed");
        }

        mods = mods == null ? "" : mods;
        double clockRate = clockRate(mods);
        long sourceDurationMs = Math.round(OUTPUT_DURATION_SECONDS * clockRate * 1000.0);
        long firstObject = beatmap.getHitObjects().getFirst().getTime();
        long lastObject = beatmap.getHitObjects().getLast().getTime();

        if (lastObject - firstObject <= sourceDurationMs) {
            double start = Math.max(0, firstObject - 1000) / 1000.0;
            double end = (lastObject + 1000) / 1000.0;
            return new PreviewSegment(start, end, "full-map");
        }

        List<WindowDifficulty> windows = sampledWindowDifficulties(beatmap, sourceDurationMs);
        WindowDifficulty selected = windows.stream()
                .max(Comparator.comparingDouble(window -> previewScore(beatmap, window)))
                .orElseThrow(() -> new IllegalArgumentException("Unable to select a preview segment"));

        double kiaiCoverage = kiaiCoverage(beatmap, selected.start(), selected.end());
        return new PreviewSegment(
                selected.start() / 1000.0,
                selected.end() / 1000.0,
                kiaiCoverage >= 0.5 ? "kiai" : "high-pressure"
        );
    }

    private static double previewScore(OsuBeatmap beatmap, WindowDifficulty window) {
        double pressure = window.pp() + window.starRating() * 10.0;
        return pressure * (1.0 + 0.25 * kiaiCoverage(beatmap, window.start(), window.end()));
    }

    private static List<WindowDifficulty> sampledWindowDifficulties(OsuBeatmap beatmap, long durationMs)
            throws AnalyzeException {
        int objectCount = beatmap.getHitObjects().size();
        int stride = Math.max(1,
                (objectCount + MAX_DIFFICULTY_CANDIDATES - 1) / MAX_DIFFICULTY_CANDIDATES);
        long lastObject = beatmap.getHitObjects().getLast().getTime();
        List<WindowDifficulty> windows = new ArrayList<>();

        try {
            for (int i = 0; i < objectCount; i += stride) {
                long start = beatmap.getHitObjects().get(i).getTime();
                long end = start + durationMs;
                if (end > lastObject) {
                    break;
                }
                var difficulty = BeatmapAnalyzer.calculateWindowDifficulty(beatmap, start, end);
                windows.add(new WindowDifficulty(start, end, difficulty.getKey(), difficulty.getValue()));
            }
        } catch (RuntimeException e) {
            throw new AnalyzeException("Failed to analyze preview candidates", e);
        }
        return windows;
    }

    private static double kiaiCoverage(OsuBeatmap beatmap, long start, long end) {
        if (end <= start || beatmap.getTimingPoints() == null || beatmap.getTimingPoints().isEmpty()) {
            return 0.0;
        }

        List<OsuBeatmap.TimingPoint> points = beatmap.getTimingPoints().stream()
                .sorted(Comparator.comparingLong(OsuBeatmap.TimingPoint::time))
                .toList();
        boolean kiai = false;
        int index = 0;
        while (index < points.size() && points.get(index).time() <= start) {
            kiai = (points.get(index).effects() & 1) != 0;
            index++;
        }

        long cursor = start;
        long kiaiDuration = 0;
        while (cursor < end) {
            long next = index < points.size() ? Math.min(end, points.get(index).time()) : end;
            if (kiai && next > cursor) {
                kiaiDuration += next - cursor;
            }
            cursor = next;
            while (index < points.size() && points.get(index).time() <= cursor) {
                kiai = (points.get(index).effects() & 1) != 0;
                index++;
            }
        }
        return (double) kiaiDuration / (end - start);
    }

    private static double clockRate(String mods) {
        if (mods.contains("DT") || mods.contains("NC")) {
            return 1.5;
        }
        if (mods.contains("HT")) {
            return 0.75;
        }
        return 1.0;
    }

    private static void requireAtMostOne(Set<String> mods, String... mutuallyExclusive) {
        int count = 0;
        for (String mod : mutuallyExclusive) {
            if (mods.contains(mod)) {
                count++;
            }
        }
        if (count > 1) {
            throw new IllegalArgumentException("Conflicting Mods: " + String.join("/", mutuallyExclusive));
        }
    }

    public record PreviewSegment(double start, double end, String reason) {
    }
}
