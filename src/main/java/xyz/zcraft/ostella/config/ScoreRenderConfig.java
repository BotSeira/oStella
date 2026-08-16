package xyz.zcraft.ostella.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ScoreRenderConfig(
        boolean enabled,
        String rendererUrl,
        String apiKey,
        String configPath,
        List<String> workers
) {
    public ScoreRenderConfig {
        rendererUrl = rendererUrl == null || rendererUrl.isBlank()
                ? "http://localhost:8722"
                : normalizeUrl(rendererUrl);
        apiKey = apiKey == null ? "" : apiKey;
        configPath = configPath == null ? "" : configPath;
        workers = workers == null ? List.of() : workers.stream()
                .filter(url -> url != null && !url.isBlank())
                .map(ScoreRenderConfig::normalizeUrl)
                .distinct()
                .toList();
        if (workers.isEmpty()) {
            workers = List.of(rendererUrl);
        }
    }

    public ScoreRenderConfig(boolean enabled, String rendererUrl, String apiKey, String configPath) {
        this(enabled, rendererUrl, apiKey, configPath, List.of());
    }

    private static String normalizeUrl(String url) {
        return url.replaceAll("/+$", "");
    }
}
