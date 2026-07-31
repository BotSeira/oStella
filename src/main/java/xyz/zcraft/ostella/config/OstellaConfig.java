package xyz.zcraft.ostella.config;

public record OstellaConfig(
        int requestPerSecond,
        int replayRequestIntervalMillis,
        int replayMaxConcurrent,
        int renderWorkers,
        boolean debugMode,
        boolean safeFlags
) {
    public OstellaConfig {
        requestPerSecond = Math.max(1, requestPerSecond);
        replayRequestIntervalMillis = replayRequestIntervalMillis > 0
                ? replayRequestIntervalMillis
                : 2500;
        replayMaxConcurrent = Math.max(1, replayMaxConcurrent);
    }
}
