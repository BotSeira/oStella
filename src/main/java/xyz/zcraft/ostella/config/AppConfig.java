package xyz.zcraft.ostella.config;

import java.util.List;

public record AppConfig(
        OstellaConfig ostella,
        OsuConfig osu,
        ScoreRenderConfig replayRender,
        WebserverConfig webserver,
        PerfPlusConfig performancePlus,
        List<PpCalculatorConfig> ppCalculators
) {
    public AppConfig {
        if (performancePlus == null) performancePlus = new PerfPlusConfig("");
    }
}

