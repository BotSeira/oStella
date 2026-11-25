package xyz.zcraft.ostella.service;

import com.google.gson.Gson;
import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.ostella.config.OsuConfig;
import xyz.zcraft.ostella.config.PpCalculatorConfig;
import xyz.zcraft.ostella.data.PpCalculation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PpCalculatorService implements AutoCloseable {
    @Getter
    private final String name;
    @Getter
    private final String description;
    private final PpCalculatorConfig config;
    private final OsuConfig osuConfig;
    private static final Gson GSON = new Gson();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<Process> activeProcesses = new HashSet<>();

    public PpCalculatorService(PpCalculatorConfig config, OsuConfig osuConfig) {
        this.name = config.name();
        this.description = config.description();
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
        }, executor);
    }

    private static final Logger LOG = LogManager.getLogger(PpCalculatorService.class);

    public PpCalculation calculate(long uid) throws IOException, InterruptedException {
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

        return GSON.fromJson(output, PpCalculation.class);
    }

    @Override
    public synchronized void close() {
        // Let queued futures complete exceptionally when they reach the shutdown check.
        executor.shutdown();
        activeProcesses.forEach(PpCalculatorService::stopProcess);
    }

    private static void stopProcess(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
