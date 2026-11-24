package xyz.zcraft.ostella.service;

import com.google.gson.Gson;
import xyz.zcraft.ostella.config.OsuConfig;
import xyz.zcraft.ostella.config.PpCalculatorConfig;
import xyz.zcraft.ostella.data.PpCalculation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PpCalculatorService {
    private final PpCalculatorConfig config;
    private final OsuConfig osuConfig;
    private static final Gson GSON = new Gson();
    private final ExecutorService calculateCalculator = Executors.newSingleThreadExecutor();

    public PpCalculatorService(PpCalculatorConfig config, OsuConfig osuConfig) {
        this.config = config;
        this.osuConfig = osuConfig;
    }

    public CompletableFuture<PpCalculation> enqueueCalculation(long uid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return calculate(uid);
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }, calculateCalculator);
    }

    public PpCalculation calculate(long uid) throws IOException, InterruptedException {
        var command = new ArrayList<>(Arrays.stream(config.cmd().split(" ")).toList());

        command.addAll(List.of(
                "profile", String.valueOf(uid),
                osuConfig.clientId(), osuConfig.clientSecret(),
                "-r", "0",
                "--json",
                "--beatmap-cache", CacheService.BEATMAP_CACHE.toAbsolutePath().toString()
        ));

        final ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(Path.of(config.path()).toFile());

        final Process process = processBuilder.redirectErrorStream(true).start();

        final String outputRaw;
        try (var is = process.getInputStream()) {
            outputRaw = new String(is.readAllBytes());
        }

        process.waitFor();

        if (!outputRaw.contains("{")) {
            throw new RuntimeException("Error running calculation");
        }

        var output = outputRaw.substring(outputRaw.indexOf("{"));

        return GSON.fromJson(output, PpCalculation.class);
    }
}
