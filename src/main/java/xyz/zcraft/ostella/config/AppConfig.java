package xyz.zcraft.ostella.config;

public record AppConfig(
        OstellaConfig ostella,
        OsuConfig osu,
        ScoreRenderConfig replayRender,
        WebserverConfig webserver,
        PerfPlusConfig performancePlus
) {
}

