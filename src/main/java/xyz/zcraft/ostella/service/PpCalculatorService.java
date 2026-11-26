package xyz.zcraft.ostella.service;

import com.google.gson.Gson;
import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.ostella.config.OsuConfig;
import xyz.zcraft.ostella.config.PpCalculatorConfig;
import xyz.zcraft.ostella.data.CalculatedSingleScore;
import xyz.zcraft.ostella.data.ProfileCalculation;
import xyz.zcraft.osu.model.Mod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PpCalculatorService implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private static final Logger LOG = LogManager.getLogger(PpCalculatorService.class);
    @Getter
    private final String name;
    @Getter
    private final String description;
    private final PpCalculatorConfig config;
    private final OsuConfig osuConfig;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<Process> activeProcesses = new HashSet<>();

    public PpCalculatorService(PpCalculatorConfig config, OsuConfig osuConfig) {
        this.name = config.name();
        this.description = config.description();
        this.config = config;
        this.osuConfig = osuConfig;
    }

    private static void stopProcess(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    public CompletableFuture<ProfileCalculation> enqueueProfileCalculation(long uid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return calculateProfile(uid);
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }, executor);
    }

    public CompletableFuture<CalculatedSingleScore> enqueueScoreCalculation(
            Path beatmap, List<Mod> mods,
            int combo, int goods, int mehs, int misses, double accuracy, int largeTickMisses, int sliderTailMisses
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return calculateSingleScore(beatmap, mods, combo, goods, mehs, misses, accuracy, largeTickMisses, sliderTailMisses);
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }, executor);
    }

    public ProfileCalculation calculateProfile(long uid) throws IOException, InterruptedException {
        LOG.info("Starting PP recalculation for user {} on version {}", uid, name);

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

        final Process process;
        synchronized (this) {
            if (executor.isShutdown()) throw new CancellationException("PP calculator is closed");
            process = processBuilder.redirectErrorStream(true).start();
            activeProcesses.add(process);
        }

        final String outputRaw;
        try (var is = process.getInputStream()) {
            outputRaw = new String(is.readAllBytes());
            process.waitFor();
        } finally {
            synchronized (this) {
                if (process.isAlive()) stopProcess(process);
                activeProcesses.remove(process);
            }
        }

        if (!outputRaw.contains("{")) {
            throw new RuntimeException("Error running calculation");
        }

        var output = outputRaw.substring(outputRaw.indexOf("{"));

        final ProfileCalculation profileCalculation = GSON.fromJson(output, ProfileCalculation.class);

        LOG.info("Calculation completed for user {} with {} scores", uid, profileCalculation.scores().size());

        return profileCalculation;
    }

    public CalculatedSingleScore calculateSingleScore(
            Path beatmap, List<Mod> mods, int combo, int goods, int mehs, int misses, double accuracy,
            int largeTickMisses, int sliderTailMisses
    ) throws IOException, InterruptedException {
        LOG.info("Starting PP recalculation for score on beatmap {} on version {}", beatmap.getFileName().toString(), name);

        var command = new ArrayList<>(Arrays.stream(config.cmd().split(" ")).toList());

        command.addAll(List.of(
                "simulate", "osu",
                "--mehs", String.valueOf(mehs),
                "--goods", String.valueOf(goods),
                "--misses", String.valueOf(misses),
                "--misses", String.valueOf(misses),
                "--accuracy", String.valueOf(accuracy),
                "--combo", String.valueOf(combo),
                "--large-tick-misses", String.valueOf(largeTickMisses),
                "--slider-tail-misses", String.valueOf(sliderTailMisses),
                "--json"
        ));

        for (Mod mod : mods) {
            command.add("--mod");
            command.add(mod.getAcronym().toLowerCase());
            if (mod.getSettings() != null && !mod.getSettings().isEmpty()) {
                mod.getSettings().forEach((k, v) -> {
                    command.add("--mod-option");
                    command.add(mod.getAcronym().toUpperCase() + "_" + k + "=" + v);
                });
            }
        }

        final ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(Path.of(config.path()).toFile());

        final Process process;
        synchronized (this) {
            if (executor.isShutdown()) throw new CancellationException("PP calculator is closed");
            process = processBuilder.redirectErrorStream(true).start();
            activeProcesses.add(process);
        }

        final String outputRaw;
        try (var is = process.getInputStream()) {
            outputRaw = new String(is.readAllBytes());
            process.waitFor();
        } finally {
            synchronized (this) {
                if (process.isAlive()) stopProcess(process);
                activeProcesses.remove(process);
            }
        }

        if (!outputRaw.contains("{")) {
            throw new RuntimeException("Error running calculation");
        }

        var output = outputRaw.substring(outputRaw.indexOf("{"));

        final CalculatedSingleScore calc = GSON.fromJson(output, CalculatedSingleScore.class);

        LOG.info("Calculation completed on beatmap {}", calc.score().beatmapId());

        return calc;
    }

    @Override
    public synchronized void close() {
        executor.shutdownNow();
        activeProcesses.forEach(PpCalculatorService::stopProcess);
    }
}
