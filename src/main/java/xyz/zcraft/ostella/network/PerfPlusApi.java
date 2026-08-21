package xyz.zcraft.ostella.network;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import xyz.zcraft.osu.model.Mod;
import xyz.zcraft.osu.model.Score;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class PerfPlusApi {
    private static final Gson GSON = new Gson();
    private final HttpClient client;
    private final URI calculationEndpoint;

    public PerfPlusApi(String endpoint) {
        this(endpoint, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build());
    }

    PerfPlusApi(String endpoint, HttpClient client) {
        this.client = Objects.requireNonNull(client);
        this.calculationEndpoint = endpoint == null || endpoint.isBlank()
                ? null
                : URI.create(endpoint.strip().replaceAll("/+$", "") + "/batch/calculation");
    }

    public boolean isConfigured() {
        return calculationEndpoint != null;
    }

    public CompletableFuture<PerformancePlus> calculate(Score score) {
        return calculate(toRequest(score));
    }

    public CompletableFuture<PerformancePlus> calculateBeatmap(long beatmapId, String mods) {
        if (beatmapId <= 0) throw new IllegalArgumentException("beatmapId must be positive");
        List<ModRequest> modRequests = parseModAcronyms(mods).stream()
                .map(acronym -> new ModRequest(acronym, Map.of()))
                .toList();
        return calculate(new ScoreRequest(String.valueOf(beatmapId), modRequests, null, 0, 0, 0));
    }

    public static List<String> parseModAcronyms(String mods) {
        if (mods == null || mods.isBlank()) return List.of();
        String normalized = mods.toUpperCase(Locale.ROOT).replaceAll("[+,\\s]", "");
        if ("NM".equals(normalized)) return List.of();
        if (!normalized.matches("(?:[A-Z0-9]{2})+")) {
            throw new IllegalArgumentException("Mods must be two-character acronyms, for example HDDT");
        }

        return java.util.stream.IntStream.range(0, normalized.length() / 2)
                .mapToObj(index -> normalized.substring(index * 2, index * 2 + 2))
                .toList();
    }

    private CompletableFuture<PerformancePlus> calculate(ScoreRequest score) {
        if (!isConfigured()) return CompletableFuture.completedFuture(null);

        HttpRequest request = HttpRequest.newBuilder(calculationEndpoint)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        GSON.toJson(List.of(score)), StandardCharsets.UTF_8))
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> parseResponse(response.statusCode(), response.body()));
    }

    private static ScoreRequest toRequest(Score score) {
        Objects.requireNonNull(score, "score");
        Long beatmapId = score.getBeatmapId();
        if (beatmapId == null && score.getBeatmap() != null) beatmapId = score.getBeatmap().getId();
        if (beatmapId == null) throw new IllegalArgumentException("Score has no beatmap id");

        List<ModRequest> mods = score.getMods() == null
                ? List.of()
                : score.getMods().stream().map(PerfPlusApi::toRequest).toList();
        Score.ScoreStatistics statistics = score.getStatistics();

        return new ScoreRequest(
                String.valueOf(beatmapId),
                mods,
                score.getMaxCombo(),
                statistic(statistics, "miss"),
                statistic(statistics, "meh"),
                statistic(statistics, "ok")
        );
    }

    private static ModRequest toRequest(Mod mod) {
        Map<String, String> settings = new LinkedHashMap<>();
        if (mod.getSettings() != null) {
            mod.getSettings().forEach((key, value) -> {
                if (value != null) settings.put(key, String.valueOf(value));
            });
        }
        return new ModRequest(mod.getAcronym(), settings);
    }

    private static long statistic(Score.ScoreStatistics statistics, String name) {
        return statistics == null ? 0 : statistics.getOrDefault(name, 0L);
    }

    private static PerformancePlus parseResponse(int statusCode, String body) {
        if (statusCode < 200 || statusCode >= 300) {
            String suffix = body == null || body.isBlank() ? "" : ": " + body;
            throw new IllegalStateException("performance+ returned HTTP " + statusCode + suffix);
        }

        try {
            CalculationResponse[] responses = GSON.fromJson(body, CalculationResponse[].class);
            if (responses == null || responses.length == 0 || responses[0].performance() == null) {
                throw new IllegalStateException("performance+ returned no performance data");
            }
            return PerformancePlus.from(responses[0]);
        } catch (JsonParseException e) {
            throw new IllegalStateException("performance+ returned invalid JSON", e);
        }
    }

    private record ScoreRequest(
            String beatmapId,
            List<ModRequest> mods,
            Long combo,
            long misses,
            long mehs,
            long oks
    ) {
    }

    private record ModRequest(String acronym, Map<String, String> settings) {
    }

    private record CalculationResponse(
            double accuracy,
            double combo,
            PerformanceResponse performance
    ) {
    }

    private record PerformanceResponse(
            double aim,
            double jumpAim,
            double flowAim,
            double precision,
            double speed,
            double stamina,
            double accuracy,
            double total
    ) {
    }

    public record PerformancePlus(
            double total,
            List<SkillPerformance> skills,
            double accuracy,
            double combo
    ) {
        private static PerformancePlus from(CalculationResponse calculation) {
            PerformanceResponse response = calculation.performance();
            List<RawSkill> rawSkills = List.of(
                    new RawSkill("Aim", response.aim()),
                    new RawSkill("Jump Aim", response.jumpAim()),
                    new RawSkill("Flow Aim", response.flowAim()),
                    new RawSkill("Precision", response.precision()),
                    new RawSkill("Speed", response.speed()),
                    new RawSkill("Stamina", response.stamina()),
                    new RawSkill("Accuracy", response.accuracy())
            );
            double skillTotal = rawSkills.stream().mapToDouble(RawSkill::pp).sum();
            List<SkillPerformance> skills = rawSkills.stream()
                    .map(skill -> new SkillPerformance(
                            skill.name(),
                            skill.pp(),
                            skillTotal > 0 ? skill.pp() / skillTotal * 100 : 0))
                    .toList();
            return new PerformancePlus(
                    response.total(), skills, calculation.accuracy(), calculation.combo());
        }
    }

    public record SkillPerformance(String name, double pp, double percentage) {
    }

    private record RawSkill(String name, double pp) {
    }
}
