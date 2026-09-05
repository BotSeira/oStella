package xyz.zcraft.ostella.data;

import xyz.zcraft.ostella.service.CacheService;
import xyz.zcraft.osu.model.Mod;
import xyz.zcraft.osu.model.Score;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A validated filter applied to a score-list response.
 */
public final class ScoreFilter {
    private static final Pattern FILTER_PATTERN = Pattern.compile(
            "(?i)^(acc(?:uracy)?|combo|pp|time|length|len|star|stars|sr|bpm|miss|misses|score|mod|mods|rank|replay"
                    + "|title|artist|mapper|genre|language|video|storyboard|fullcombo|ar|od|cs|hp)"
                    + "(>=|<=|!=|!~|>|<|=|~)(.+)$"
    );
    private static final Pattern DURATION_PATTERN = Pattern.compile("(?i)^(?:(\\d+)m)?(?:(\\d+(?:\\.\\d+)?)s)?$");
    private static final Pattern MOD_PATTERN = Pattern.compile("[A-Z]{2}");

    private final Field field;
    private final Operator operator;
    private final double numericValue;
    private final Set<String> textValues;
    private final String displayText;

    private ScoreFilter(
            Field field,
            Operator operator,
            double numericValue,
            Set<String> textValues,
            String displayText
    ) {
        this.field = field;
        this.operator = operator;
        this.numericValue = numericValue;
        this.textValues = textValues;
        this.displayText = displayText;
    }

    public static List<ScoreFilter> parseList(String encodedFilters) {
        if (encodedFilters == null || encodedFilters.isBlank()) {
            return List.of();
        }

        List<ScoreFilter> filters = new ArrayList<>();
        for (String token : encodedFilters.split(",", -1)) {
            if (token.isBlank()) {
                throw new IllegalArgumentException("Filter cannot be empty");
            }
            filters.add(parse(token));
        }
        return List.copyOf(filters);
    }

    public static ScoreFilter parse(String token) {
        Matcher matcher = FILTER_PATTERN.matcher(token.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid score filter: " + token);
        }

        Field field = Field.from(matcher.group(1));
        Operator operator = Operator.from(matcher.group(2));
        String value = matcher.group(3).trim();

        if (field == Field.MODS) {
            return parseMods(field, operator, value);
        }
        if (field == Field.RANK) {
            return parseRank(field, operator, value);
        }
        if (field.isMetadataText()) {
            return parseMetadataText(field, operator, value);
        }
        if (field.isBoolean()) {
            return parseBoolean(field, operator, value);
        }
        if (!operator.isNumeric()) {
            throw new IllegalArgumentException("Filter " + field.label + " does not support operator " + operator.symbol);
        }

        double numericValue = field == Field.LENGTH ? parseDuration(value) : parseNumber(value, token);
        validateRange(field, numericValue);
        return new ScoreFilter(
                field,
                operator,
                numericValue,
                Set.of(),
                field.label + " " + operator.display + " " + formatValue(field, numericValue)
        );
    }

    private static ScoreFilter parseMods(Field field, Operator operator, String value) {
        if (!operator.isText()) {
            throw new IllegalArgumentException("Mods only supports ~, !~, = and !=");
        }

        Set<String> mods = parseMods(value);
        String displayValue = mods.isEmpty() ? "NM" : String.join("", mods.stream().sorted().toList());
        return new ScoreFilter(
                field,
                operator,
                Double.NaN,
                mods,
                field.label + " " + operator.display + " " + displayValue
        );
    }

    private static ScoreFilter parseRank(Field field, Operator operator, String value) {
        if (operator != Operator.EQUAL && operator != Operator.NOT_EQUAL) {
            throw new IllegalArgumentException("Rank only supports = and !=");
        }
        String rank = value.toUpperCase(Locale.ROOT);
        if (!Set.of("XH", "X", "SH", "S", "A", "B", "C", "D", "F").contains(rank)) {
            throw new IllegalArgumentException("Invalid rank: " + value);
        }
        return new ScoreFilter(field, operator, Double.NaN, Set.of(rank), field.label + " " + operator.display + " " + rank);
    }

    private static ScoreFilter parseMetadataText(Field field, Operator operator, String value) {
        if (!operator.isText()) {
            throw new IllegalArgumentException(field.label + " only supports ~, !~, = and !=");
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(field.label + " cannot be empty");
        }
        return new ScoreFilter(
                field,
                operator,
                Double.NaN,
                Set.of(value.toLowerCase(Locale.ROOT)),
                field.label + " " + operator.display + " " + value
        );
    }

    private static ScoreFilter parseBoolean(Field field, Operator operator, String value) {
        if (operator != Operator.EQUAL && operator != Operator.NOT_EQUAL) {
            throw new IllegalArgumentException(field.label + " only supports = and !=");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!normalized.equals("true") && !normalized.equals("false")) {
            throw new IllegalArgumentException(field.label + " must be true or false");
        }
        return new ScoreFilter(
                field,
                operator,
                Double.NaN,
                Set.of(normalized),
                field.label + " " + operator.display + " " + normalized
        );
    }

    private static Set<String> parseMods(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace("+", "").replace(" ", "");
        if (normalized.equals("NM")) {
            return Set.of();
        }
        if (normalized.isEmpty() || normalized.length() % 2 != 0) {
            throw new IllegalArgumentException("Invalid mods: " + value);
        }

        Set<String> mods = new HashSet<>();
        for (int i = 0; i < normalized.length(); i += 2) {
            String acronym = normalized.substring(i, i + 2);
            if (!MOD_PATTERN.matcher(acronym).matches()) {
                throw new IllegalArgumentException("Invalid mods: " + value);
            }
            mods.add(acronym);
        }
        return Set.copyOf(mods);
    }

    private static double parseNumber(String value, String token) {
        try {
            double number = Double.parseDouble(value);
            if (!Double.isFinite(number)) {
                throw new NumberFormatException();
            }
            return number;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid numeric value in filter: " + token, e);
        }
    }

    private static double parseDuration(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains(":")) {
            String[] parts = normalized.split(":", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid beatmap length: " + value);
            }
            double minutes = parseNumber(parts[0], value);
            double seconds = parseNumber(parts[1], value);
            if (minutes < 0 || seconds < 0 || seconds >= 60) {
                throw new IllegalArgumentException("Invalid beatmap length: " + value);
            }
            return minutes * 60 + seconds;
        }

        Matcher matcher = DURATION_PATTERN.matcher(normalized);
        if (matcher.matches() && (matcher.group(1) != null || matcher.group(2) != null)) {
            double minutes = matcher.group(1) == null ? 0 : parseNumber(matcher.group(1), value);
            double seconds = matcher.group(2) == null ? 0 : parseNumber(matcher.group(2), value);
            return minutes * 60 + seconds;
        }
        return parseNumber(normalized, value);
    }

    private static void validateRange(Field field, double value) {
        if (value < 0) {
            throw new IllegalArgumentException(field.label + " cannot be negative");
        }
        if (field == Field.ACCURACY && value > 100) {
            throw new IllegalArgumentException("Accuracy must be between 0 and 100");
        }
    }

    private static String formatValue(Field field, double value) {
        return switch (field) {
            case ACCURACY -> formatNumber(value) + "%";
            case COMBO -> formatNumber(value) + "x";
            case PP -> formatNumber(value) + "pp";
            case LENGTH -> formatDuration(value);
            case STAR -> formatNumber(value) + "★";
            case BPM -> formatNumber(value) + " BPM";
            case MISS -> formatNumber(value) + " miss";
            case SCORE, AR, CS, HP, OD -> formatNumber(value);
            case MODS, RANK, TITLE, ARTIST, MAPPER, GENRE, LANGUAGE, VIDEO, STORYBOARD, FULL_COMBO, REPLAY ->
                    throw new IllegalStateException("Text filter has no numeric value");
        };
    }

    private static String formatNumber(double value) {
        if (value == Math.rint(value)) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%s", value);
    }

    private static String formatDuration(double value) {
        long seconds = Math.round(value);
        return "%d:%02d".formatted(seconds / 60, seconds % 60);
    }

    public boolean matches(Score score) {
        if (score == null) {
            return false;
        }
        return switch (field) {
            case ACCURACY -> score.getAccuracy() != null && compare(score.getAccuracy() * 100);
            case COMBO -> score.getMaxCombo() != null && compare(score.getMaxCombo());
            case PP -> score.getPp() != null && compare(score.getPp());
            case LENGTH -> score.getBeatmap() != null && score.getBeatmap().getTotalLength() != null
                    && compare(score.getBeatmap().getTotalLength());
            case STAR -> score.getBeatmap() != null && score.getBeatmap().getDifficultyRating() != null
                    && compare(score.getBeatmap().getDifficultyRating());
            case AR -> score.getBeatmap() != null && score.getBeatmap().getAr() != null
                    && compare(score.getBeatmap().getAr());
            case CS -> score.getBeatmap() != null && score.getBeatmap().getCs() != null
                    && compare(score.getBeatmap().getCs());
            case HP -> score.getBeatmap() != null && score.getBeatmap().getDrain() != null
                    && compare(score.getBeatmap().getDrain());
            case OD -> score.getBeatmap() != null && score.getBeatmap().getAccuracy() != null
                    && compare(score.getBeatmap().getAccuracy());
            case BPM -> score.getBeatmap() != null && score.getBeatmap().getBpm() != null
                    && compare(score.getBeatmap().getBpm());
            case MISS -> score.getStatistics() != null && compare(score.getStatistics().getOrDefault("miss", 0L));
            case SCORE -> score.getTotalScore() != null && compare(score.getTotalScore());
            case MODS -> compareMods(score.getMods());
            case RANK -> compareText(score.getRank());
            case TITLE -> score.getBeatmapset() != null
                    && compareText(score.getBeatmapset().getTitle(), score.getBeatmapset().getTitleUnicode());
            case ARTIST -> score.getBeatmapset() != null
                    && compareText(score.getBeatmapset().getArtist(), score.getBeatmapset().getArtistUnicode());
            case MAPPER -> score.getBeatmapset() != null && compareText(score.getBeatmapset().getCreator());
            case GENRE -> score.getBeatmapset() != null && score.getBeatmapset().getGenre() != null
                    && compareText(score.getBeatmapset().getGenre().getName());
            case LANGUAGE -> score.getBeatmapset() != null && score.getBeatmapset().getLanguage() != null
                    && compareText(score.getBeatmapset().getLanguage().getName());
            case VIDEO -> score.getBeatmapset() != null && compareBoolean(score.getBeatmapset().getVideo());
            case STORYBOARD -> score.getBeatmapset() != null && compareBoolean(score.getBeatmapset().getStoryboard());
            case REPLAY -> compareBoolean(score.getHasReplay() || CacheService.hasReplayCache(score.getId()));
            case FULL_COMBO -> compareBoolean(score.getIsPerfectCombo());
        };
    }

    private boolean compare(double actual) {
        int comparison = Double.compare(actual, numericValue);
        return switch (operator) {
            case GREATER -> comparison > 0;
            case GREATER_OR_EQUAL -> comparison >= 0;
            case LESS -> comparison < 0;
            case LESS_OR_EQUAL -> comparison <= 0;
            case EQUAL -> comparison == 0;
            case NOT_EQUAL -> comparison != 0;
            case CONTAINS, NOT_CONTAINS -> throw new IllegalStateException("Text operator used for numeric filter");
        };
    }

    private boolean compareMods(List<Mod> scoreMods) {
        Set<String> actual = new HashSet<>();
        if (scoreMods != null) {
            scoreMods.stream()
                    .map(Mod::getAcronym)
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.toUpperCase(Locale.ROOT))
                    .forEach(actual::add);
        }

        Set<String> semanticActual = new HashSet<>(actual);
        if (actual.contains("NC")) semanticActual.add("DT");
        if (actual.contains("PF")) semanticActual.add("SD");

        return switch (operator) {
            case CONTAINS -> semanticActual.containsAll(textValues);
            case NOT_CONTAINS -> !semanticActual.containsAll(textValues);
            case EQUAL -> actual.equals(textValues);
            case NOT_EQUAL -> !actual.equals(textValues);
            default -> throw new IllegalStateException("Numeric operator used for mods filter");
        };
    }

    private boolean compareText(String actual) {
        return compareText(new String[]{actual});
    }

    private boolean compareText(String... actualValues) {
        String expected = textValues.iterator().next().toLowerCase(Locale.ROOT);
        boolean matches = false;
        boolean present = false;
        for (String actual : actualValues) {
            if (actual == null) continue;
            present = true;
            String normalized = actual.toLowerCase(Locale.ROOT);
            if (operator == Operator.CONTAINS || operator == Operator.NOT_CONTAINS) {
                matches |= normalized.contains(expected);
            } else {
                matches |= normalized.equals(expected);
            }
        }
        if (!present) return false;
        return switch (operator) {
            case EQUAL, CONTAINS -> matches;
            case NOT_EQUAL, NOT_CONTAINS -> !matches;
            default -> throw new IllegalStateException("Numeric operator used for text filter");
        };
    }

    private boolean compareBoolean(Boolean actual) {
        if (actual == null) return false;
        boolean equal = actual == Boolean.parseBoolean(textValues.iterator().next());
        return (operator == Operator.EQUAL) == equal;
    }

    public String displayText() {
        return displayText;
    }

    private enum Field {
        ACCURACY("Accuracy"),
        COMBO("Combo"),
        PP("PP"),
        LENGTH("Beatmap length"),
        STAR("Star rating"),
        CS("Circle Size"),
        AR("Approach Rate"),
        HP("HP Drain"),
        OD("Overall Difficulty"),
        BPM("BPM"),
        MISS("Misses"),
        SCORE("Score"),
        MODS("Mods"),
        RANK("Rank"),
        TITLE("Title"),
        ARTIST("Artist"),
        MAPPER("Mapper"),
        GENRE("Genre"),
        LANGUAGE("Language"),
        VIDEO("Video"),
        STORYBOARD("Storyboard"),
        REPLAY("Replay"),
        FULL_COMBO("Full combo");

        private final String label;

        Field(String label) {
            this.label = label;
        }

        static Field from(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "acc", "accuracy" -> ACCURACY;
                case "combo" -> COMBO;
                case "pp" -> PP;
                case "time", "length", "len" -> LENGTH;
                case "star", "stars", "sr" -> STAR;
                case "bpm" -> BPM;
                case "miss", "misses" -> MISS;
                case "score" -> SCORE;
                case "mod", "mods" -> MODS;
                case "rank" -> RANK;
                case "title" -> TITLE;
                case "artist" -> ARTIST;
                case "mapper" -> MAPPER;
                case "genre" -> GENRE;
                case "language" -> LANGUAGE;
                case "video" -> VIDEO;
                case "storyboard" -> STORYBOARD;
                case "replay" -> REPLAY;
                case "fullcombo" -> FULL_COMBO;
                default -> throw new IllegalArgumentException("Unknown filter field: " + value);
            };
        }

        boolean isMetadataText() {
            return this == TITLE || this == ARTIST || this == MAPPER || this == GENRE || this == LANGUAGE;
        }

        boolean isBoolean() {
            return this == VIDEO || this == STORYBOARD || this == FULL_COMBO || this == REPLAY;
        }
    }

    private enum Operator {
        GREATER(">", ">"),
        GREATER_OR_EQUAL(">=", "≥"),
        LESS("<", "<"),
        LESS_OR_EQUAL("<=", "≤"),
        EQUAL("=", "="),
        NOT_EQUAL("!=", "≠"),
        CONTAINS("~", "contains"),
        NOT_CONTAINS("!~", "does not contain");

        private final String symbol;
        private final String display;

        Operator(String symbol, String display) {
            this.symbol = symbol;
            this.display = display;
        }

        static Operator from(String value) {
            for (Operator operator : values()) {
                if (operator.symbol.equals(value)) return operator;
            }
            throw new IllegalArgumentException("Unknown filter operator: " + value);
        }

        boolean isNumeric() {
            return this != CONTAINS && this != NOT_CONTAINS;
        }

        boolean isText() {
            return this == CONTAINS || this == NOT_CONTAINS || this == EQUAL || this == NOT_EQUAL;
        }
    }
}
