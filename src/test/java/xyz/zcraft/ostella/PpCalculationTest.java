package xyz.zcraft.ostella;

import org.junit.jupiter.api.Test;
import xyz.zcraft.ostella.config.AppConfig;
import xyz.zcraft.ostella.config.ConfigLoader;
import xyz.zcraft.ostella.data.PpCalculation;
import xyz.zcraft.ostella.service.PpCalculatorService;

import java.io.IOException;

public class PpCalculationTest {
    @Test
    public void test() throws IOException, InterruptedException {
        final AppConfig appConfig = ConfigLoader.loadConfig();
        try (var calculator = new PpCalculatorService(appConfig.ppCalculators().getFirst(), appConfig.osu())) {
            calculator.calculateProfile(21445688);
        }
    }
}
