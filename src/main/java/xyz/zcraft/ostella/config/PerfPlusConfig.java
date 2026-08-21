package xyz.zcraft.ostella.config;

public record PerfPlusConfig(
        String endpoint
) {
    public PerfPlusConfig {
        endpoint = endpoint == null ? "" : endpoint.strip();
    }
}
