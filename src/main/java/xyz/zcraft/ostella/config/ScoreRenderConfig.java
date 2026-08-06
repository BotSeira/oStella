package xyz.zcraft.ostella.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ScoreRenderConfig(
        boolean enabled,
        String rendererUrl,
        String apiKey,
        String configPath
) {
    public ScoreRenderConfig {
        rendererUrl = rendererUrl == null || rendererUrl.isBlank()
                ? "http://localhost:8722"
                : rendererUrl.replaceAll("/+$", "");
        apiKey = apiKey == null ? "" : apiKey;
        configPath = configPath == null ? "" : configPath;
    }
}
