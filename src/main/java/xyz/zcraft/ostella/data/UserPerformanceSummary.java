package xyz.zcraft.ostella.data;

import xyz.zcraft.ostella.util.format.ScoreFormatUtil;
import xyz.zcraft.osu.model.Score;
import xyz.zcraft.osu.model.UserExtended;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public record UserPerformanceSummary(
        int sampleSize,
        Double averagePp,
        Double averageAccuracy,
        Double averageStarRating,
        Double weightedPp,
        Double bonusPp,
        Double floorPp,
        String favoriteMods
) {
    public static UserPerformanceSummary from(UserExtended user, List<Score> scores) {
        List<Score> sample = scores == null ? List.of() : scores.stream()
                .filter(Objects::nonNull)
                .toList();

        Double averagePp = average(sample.stream().map(Score::getPp).toList());
        Double averageAccuracy = average(sample.stream().map(Score::getAccuracy).toList());
        Double averageStarRating = average(sample.stream()
                .map(Score::getBeatmap)
                .filter(Objects::nonNull)
                .map(beatmap -> beatmap.getDifficultyRating())
                .toList());
        Double floorPp = sample.stream()
                .map(Score::getPp)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);

        List<Double> weightedValues = sample.stream()
                .map(Score::getWeight)
                .filter(Objects::nonNull)
                .map(Score.Weight::getPp)
                .filter(Objects::nonNull)
                .toList();
        Double weightedPp = weightedValues.isEmpty()
                ? null
                : weightedValues.stream().mapToDouble(Double::doubleValue).sum();
        Double totalPp = user == null || user.getStatistics() == null
                ? null
                : user.getStatistics().getPp();
        Double bonusPp = totalPp == null || weightedPp == null
                ? null
                : Math.max(0.0, totalPp - weightedPp);

        String favoriteMods = sample.stream()
                .map(ScoreFormatUtil::getModString)
                .collect(Collectors.groupingBy(
                        Function.identity(),
                        LinkedHashMap::new,
                        Collectors.counting()
                ))
                .entrySet()
                .stream()
                .max(Map.Entry.<String, Long>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .orElse("--");

        return new UserPerformanceSummary(
                sample.size(),
                averagePp,
                averageAccuracy,
                averageStarRating,
                weightedPp,
                bonusPp,
                floorPp,
                favoriteMods
        );
    }

    private static Double average(List<Double> values) {
        List<Double> present = values.stream().filter(Objects::nonNull).toList();
        return present.isEmpty()
                ? null
                : present.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }
}
