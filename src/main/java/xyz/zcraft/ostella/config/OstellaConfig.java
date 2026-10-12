package xyz.zcraft.ostella.config;

public record OstellaConfig(
        String sqlitePath,
        String token,
        int requestPerSecond,
        int replayRequestIntervalMillis,
        int replayMaxConcurrent,
        int renderWorkers,
        boolean debugMode,
        boolean safeFlags
) {
    public OstellaConfig {
        sqlitePath = sqlitePath == null || sqlitePath.isBlank() ? "data/ostella.db" : sqlitePath;
        requestPerSecond = Math.max(1, requestPerSecond);
        replayRequestIntervalMillis = replayRequestIntervalMillis > 0
                ? replayRequestIntervalMillis
                : 2500;
        replayMaxConcurrent = Math.max(1, replayMaxConcurrent);
    }
}
