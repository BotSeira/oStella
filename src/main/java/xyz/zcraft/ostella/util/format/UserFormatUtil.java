package xyz.zcraft.ostella.util.format;

import lombok.Setter;
import xyz.zcraft.ostella.data.ScoreChange;
import xyz.zcraft.osu.model.User;
import xyz.zcraft.osu.model.UserExtended;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

public class UserFormatUtil {
    @Setter
    private static boolean safeFlags = false;

    public static String getFormattedJoinDate(UserExtended user) {
        if (user == null || user.getJoinDate() == null || user.getJoinDate().isEmpty()) {
            return "Unknown";
        }

        try {
            OffsetDateTime parsedDate = OffsetDateTime.parse(user.getJoinDate());
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
            return parsedDate.format(formatter);
        } catch (RuntimeException e) {
            return user.getJoinDate();
        }
    }

    public static ScoreChange getScoreChange(UserExtended user) {
        final ScoreChange scoreChange = new ScoreChange();
        final List<Long> data = Optional.ofNullable(user)
                .map(UserExtended::getRankHistory)
                .map(UserExtended.RankHistory::getData)
                .map(List::reversed)
                .orElse(List.of());

        if (data.size() < 2) {
            return scoreChange;
        }
        scoreChange.hasData[3] = true;
        scoreChange.data[3] = Math.toIntExact(data.get(1) - data.getFirst());

        if (data.size() < 7) {
            return scoreChange;
        }
        scoreChange.hasData[2] = true;
        scoreChange.data[2] = Math.toIntExact(data.get(6) - data.getFirst());

        if (data.size() < 30) {
            return scoreChange;
        }
        scoreChange.hasData[1] = true;
        scoreChange.data[1] = Math.toIntExact(data.get(29) - data.getFirst());

        if (data.size() < 90) {
            return scoreChange;
        }
        scoreChange.hasData[0] = true;
        scoreChange.data[0] = Math.toIntExact(data.get(89) - data.getFirst());

        return scoreChange;
    }

    public static String getRankStr(User user) {
        return Optional.ofNullable(user)
                .map(User::getStatisticsRulesets)
                .map(User.StatisticsRuleset::getOsu)
                .map(User.Statistics::getGlobalRank)
                .map(l -> String.format("#%,d", l))
                .orElse("");
    }

    public static boolean havePp(User user) {
        return Optional.ofNullable(user)
                .map(User::getStatisticsRulesets)
                .map(User.StatisticsRuleset::getOsu)
                .map(User.Statistics::getPp)
                .isPresent();
    }

    public static String getFlagUrl(User user) {
        String countryCode = user.getCountryCode();

        if (safeFlags && "TW".equalsIgnoreCase(countryCode)) {
            countryCode = "__";
        }

        return "https://assets.ppy.sh/old-flags/" + countryCode + ".png";
    }

    public static String formatInteger(Number value) {
        return value == null ? "--" : String.format(Locale.US, "%,d", value.longValue());
    }

    public static String formatCompactInteger(Number value) {
        if (value == null) return "--";
        long number = value.longValue();
        long absolute = Math.abs(number);
        if (absolute < 10_000) return formatInteger(number);

        double divisor;
        String suffix;
        if (absolute >= 1_000_000_000_000L) {
            divisor = 1_000_000_000_000.0;
            suffix = "T";
        } else if (absolute >= 1_000_000_000L) {
            divisor = 1_000_000_000.0;
            suffix = "B";
        } else if (absolute >= 1_000_000L) {
            divisor = 1_000_000.0;
            suffix = "M";
        } else {
            divisor = 1_000.0;
            suffix = "K";
        }

        String formatted = String.format(Locale.US, "%.1f", number / divisor);
        if (formatted.endsWith(".0")) {
            formatted = formatted.substring(0, formatted.length() - 2);
        }
        return formatted + suffix;
    }

    public static String formatRank(Number value) {
        return value == null ? "Unranked" : "#" + formatInteger(value);
    }

    public static String formatPp(Number value) {
        return value == null ? "--" : String.format(Locale.US, "%,.0f", value.doubleValue());
    }

    public static String formatAccuracy(Number value) {
        return value == null ? "--" : String.format(Locale.US, "%.2f%%", value.doubleValue());
    }

    public static String formatPlayTime(Number seconds) {
        if (seconds == null) return "--";
        return formatInteger(seconds.longValue() / 3600) + " hrs";
    }

    public static String formatLevel(UserExtended user) {
        return Optional.ofNullable(user)
                .map(UserExtended::getStatistics)
                .map(User.Statistics::getLevel)
                .map(UserExtended.Level::getCurrent)
                .map(String::valueOf)
                .orElse("--");
    }

    public static String formatLevelProgress(UserExtended user) {
        return Optional.ofNullable(user)
                .map(UserExtended::getStatistics)
                .map(User.Statistics::getLevel)
                .map(UserExtended.Level::getProgress)
                .map(progress -> progress + "% to next level")
                .orElse("Progress unavailable");
    }

    public static String formatGradeCount(UserExtended user, String grade) {
        UserExtended.GradeCounts counts = Optional.ofNullable(user)
                .map(UserExtended::getStatistics)
                .map(User.Statistics::getGradeCounts)
                .orElse(null);
        if (counts == null) return "--";
        return switch (grade.toLowerCase(Locale.ROOT)) {
            case "ssh" -> formatInteger(counts.getSsh());
            case "ss" -> formatInteger(counts.getSs());
            case "sh" -> formatInteger(counts.getSh());
            case "s" -> formatInteger(counts.getS());
            case "a" -> formatInteger(counts.getA());
            default -> "--";
        };
    }

    public static String getPeakRank(UserExtended user) {
        return Optional.ofNullable(user)
                .map(UserExtended::getRankHighest)
                .map(UserExtended.RankHighest::getRank)
                .map(UserFormatUtil::formatRank)
                .orElse("--");
    }

    public static boolean hasRankHistory(UserExtended user) {
        return rankHistory(user).size() > 1;
    }

    public static String getRankHistoryPoints(UserExtended user) {
        return buildRankHistoryPoints(user, false);
    }

    public static String getRankHistoryAreaPoints(UserExtended user) {
        return buildRankHistoryPoints(user, true);
    }

    public static List<MonthlyActivity> getRecentMonthlyActivity(UserExtended user) {
        List<UserExtended.MonthlyPlaycount> playcounts = Optional.ofNullable(user)
                .map(UserExtended::getMonthlyPlaycounts)
                .orElse(List.of());

        List<MonthlyPlaycountDate> dated = playcounts.stream()
                .map(UserFormatUtil::parseMonthlyPlaycount)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(MonthlyPlaycountDate::date))
                .toList();
        int start = Math.max(0, dated.size() - 12);
        List<MonthlyPlaycountDate> recent = dated.subList(start, dated.size());
        long max = recent.stream().mapToLong(MonthlyPlaycountDate::count).max().orElse(1L);

        return recent.stream()
                .map(item -> new MonthlyActivity(
                        item.date().format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)),
                        item.count(),
                        item.count() == 0 ? 3 : Math.max(8, Math.round(item.count() * 100.0 / max))
                ))
                .toList();
    }

    private static Optional<MonthlyPlaycountDate> parseMonthlyPlaycount(UserExtended.MonthlyPlaycount playcount) {
        if (playcount == null || playcount.getStartDate() == null || playcount.getCount() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new MonthlyPlaycountDate(
                    LocalDate.parse(playcount.getStartDate()),
                    playcount.getCount()
            ));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    private static String buildRankHistoryPoints(UserExtended user, boolean closeArea) {
        List<Long> ranks = rankHistory(user);
        if (ranks.size() < 2) return "";

        long min = ranks.stream().mapToLong(Long::longValue).min().orElse(0L);
        long max = ranks.stream().mapToLong(Long::longValue).max().orElse(min);
        double range = Math.max(1.0, max - min);
        double width = 620.0;
        double height = 132.0;
        double topPadding = 8.0;
        double chartHeight = 108.0;

        String points = java.util.stream.IntStream.range(0, ranks.size())
                .mapToObj(index -> {
                    double x = index * width / (ranks.size() - 1.0);
                    double y = topPadding + ((ranks.get(index) - min) / range) * chartHeight;
                    return String.format(Locale.ROOT, "%.1f,%.1f", x, y);
                })
                .collect(Collectors.joining(" "));

        return closeArea ? "0," + height + " " + points + " " + width + "," + height : points;
    }

    private static List<Long> rankHistory(UserExtended user) {
        return Optional.ofNullable(user)
                .map(UserExtended::getRankHistory)
                .map(UserExtended.RankHistory::getData)
                .filter(data -> !data.isEmpty())
                .orElse(List.of());
    }

    public record MonthlyActivity(String label, long count, long height) {
    }

    private record MonthlyPlaycountDate(LocalDate date, long count) {
    }
}

