package xyz.zcraft.ostella.console;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;
import xyz.zcraft.ostella.config.AppConfig;
import xyz.zcraft.ostella.config.ConfigLoader;
import xyz.zcraft.ostella.cache.CacheControlRequest;
import xyz.zcraft.ostella.cache.CacheControlResult;
import xyz.zcraft.ostella.network.WebServer;
import xyz.zcraft.ostella.service.CacheService;
import xyz.zcraft.ostella.service.ReplayService;
import xyz.zcraft.ostella.util.VersionInfo;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class OstellaConsoleProcessor {
    private static final long STARTED_AT = System.currentTimeMillis();
    private static final List<String> ROOT = List.of(
            "help", "status", "metrics", "token", "replay", "cache", "config", "log", "system", "stop");
    private static final Map<String, List<String>> SUB = Map.of(
            "token", List.of("status", "renew"),
            "replay", List.of("status", "job", "delete"),
            "cache", List.of("query", "delete", "get", "fetch", "status", "clear"),
            "config", List.of("show", "check"),
            "log", List.of("show", "level")
    );
    private final AppConfig config;
    private final OstellaConsoleAccess access;

    public OstellaConsoleProcessor(AppConfig config, OstellaConsoleAccess access) {
        this.config = Objects.requireNonNull(config);
        this.access = Objects.requireNonNull(access);
    }

    public Result execute(String line) {
        try {
            ConsoleInputParser.ParsedInput input = ConsoleInputParser.parse(line);
            if (input.size() == 0) return Result.ok("");
            return switch (input.value(0).toLowerCase(Locale.ROOT)) {
                case "help", "?" -> help(input);
                case "status" -> exact(input, 1, this::status, "Usage: status");
                case "metrics" -> exact(input, 1, this::metrics, "Usage: metrics");
                case "token" -> token(input);
                case "replay" -> replay(input);
                case "cache" -> cache(input);
                case "config" -> config(input);
                case "log" -> log(input);
                case "system" -> exact(input, 1, this::system, "Usage: system");
                case "stop", "shutdown", "exit", "quit" -> stop(input);
                default -> Result.error("Unknown console command: " + input.value(0) + ". Run 'help'.");
            };
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            LogManager.getLogger(OstellaConsoleProcessor.class).error("Console command failed", e);
            return Result.error("Command failed: " + rootMessage(e));
        }
    }

    static List<String> rootCommands() { return ROOT; }
    static List<String> subcommands(String command) {
        return SUB.getOrDefault(command.toLowerCase(Locale.ROOT), List.of());
    }

    private Result help(ConsoleInputParser.ParsedInput input) {
        if (input.size() > 2) return Result.error("Usage: help [command]");
        if (input.size() == 1) return Result.ok("""
                oStella administration console
                  status                          Service, API, renderer, replay, and cache health
                  metrics                         HTTP and asynchronous work counters
                  token <status|renew>            Inspect or asynchronously renew the osu! token
                  replay status                   Probe osuRenderer queue and worker state
                  replay job <job-id>             Find replay-render progress
                  replay delete <job-id> confirm  Delete a remote replay-render job
                  cache <query|delete|get|fetch> <type> <id>
                                                   Inspect or delete cache across oStella and workers
                  cache status                    Show each local cache area and total size
                  cache clear <area|all> confirm  Clear a selected cache area
                  config <show|check>             Show redacted config or validate config.yml
                  log <show|level>                Inspect or change the Log4J2 root level
                  system                          JVM, OS, thread, memory, version, and uptime
                  stop confirm                    Gracefully stop oStella

                Aliases: ? (help), shutdown/exit/quit (stop)
                """.stripTrailing());
        String topic = input.value(1).toLowerCase(Locale.ROOT);
        String detail = switch (topic) {
            case "status" -> "status\nShows web, osu! token, async work, image renderer, replay workers, and cache health.";
            case "metrics" -> "metrics\nShows HTTP totals plus submitted, completed, failed, and active async work.";
            case "token" -> "token status\ntoken renew\nRenewal is queued on the token worker and does not block the console.";
            case "replay" -> "replay status\nreplay job <uuid>\nreplay delete <uuid> confirm\nCommands contact configured osuRenderer workers.";
            case "cache" -> "cache <query|delete|get|fetch> <score|beatmap|beatmapset|replay> <id>\nQueries oStella followed by every configured osuRenderer worker. get includes metadata; fetch downloads into oStella and pushes beatmapsets/replays to workers; delete removes all reachable copies.\ncache status\ncache clear <beatmaps|images|replays|score-json|beatmapsets|all> confirm";
            case "config" -> "config show\nconfig check\nSecrets are redacted. Runtime changes require restart.";
            case "log" -> "log show\nlog level <trace|debug|info|warn|error>";
            case "system" -> "system\nShows local runtime information and process uptime.";
            case "stop", "shutdown", "exit", "quit" -> "stop confirm\nGracefully closes JLine, Javalin, render workers, and token polling.";
            default -> null;
        };
        return detail == null ? Result.error("No help topic named '" + input.value(1) + "'.") : Result.ok(detail);
    }

    private Result status() {
        WebServer.ServerStatus status = access.status();
        var async = status.async();
        var renderer = status.renderer();
        var cache = status.cache();
        return Result.ok("""
                oStella %s
                  Web server: %s (port %d)
                  osu! token: %s
                  HTTP: %d requests, %d failed
                  Async work: %d request(s), %d replay(s) active
                  Image renderer: %d/%d active, %d queued, %d completed
                  Replay rendering: %s, %d worker(s), %d assigned job(s)
                  Cache: %d files, %s
                  Uptime: %s
                """.formatted(VersionInfo.getVersion(), status.running() ? "RUNNING" : "STARTING/STOPPED",
                config.webserver().port(), status.tokenValid() ? "VALID" : "UNAVAILABLE",
                status.requests(), status.failures(), async.activeRequests(), async.activeReplays(),
                renderer.active(), renderer.poolSize(), renderer.queued(), renderer.completed(),
                status.replayEnabled() ? "ENABLED" : "DISABLED", status.replayWorkers(), status.assignedReplayJobs(),
                cache.totalFiles(), bytes(cache.totalBytes()), duration(System.currentTimeMillis() - STARTED_AT)).stripTrailing());
    }

    private Result metrics() {
        WebServer.ServerStatus status = access.status();
        var async = status.async();
        return Result.ok("""
                Runtime metrics
                  HTTP requests: %d total, %d failed
                  Async work: %d submitted, %d completed, %d failed
                  Active osu! requests: %d
                  Active replay requests: %d
                  Image renders completed: %d
                """.formatted(status.requests(), status.failures(), async.submitted(), async.completed(), async.failed(),
                async.activeRequests(), async.activeReplays(), status.renderer().completed()).stripTrailing());
    }

    private Result token(ConsoleInputParser.ParsedInput input) {
        if (input.size() != 2) return Result.error("Usage: token <status|renew>");
        return switch (input.value(1).toLowerCase(Locale.ROOT)) {
            case "status" -> Result.ok("osu! API token: " + (access.status().tokenValid() ? "VALID" : "UNAVAILABLE"));
            case "renew" -> access.requestTokenRenewal()
                    ? Result.ok("Token renewal queued.") : Result.error("Token manager is not running.");
            default -> Result.error("Usage: token <status|renew>");
        };
    }

    private Result replay(ConsoleInputParser.ParsedInput input) {
        if (input.size() == 2 && "status".equalsIgnoreCase(input.value(1))) {
            WebServer.ServerStatus status = access.status();
            if (!status.replayEnabled()) return Result.ok("Replay rendering is disabled.");
            return Result.ok("Replay workers: %d\nAssigned jobs: %d\nRemote queued jobs: %d".formatted(
                    status.replayWorkers(), status.assignedReplayJobs(), access.replayQueueSize()));
        }
        if (input.size() == 3 && "job".equalsIgnoreCase(input.value(1))) {
            String id = uuid(input.value(2));
            return Result.ok(formatReplayJob(id, access.replayJob(id)));
        }
        if (input.size() == 4 && "delete".equalsIgnoreCase(input.value(1))
                && "confirm".equalsIgnoreCase(input.value(3))) {
            String id = uuid(input.value(2));
            access.deleteReplayJob(id);
            return Result.ok("Replay job deletion requested: " + id);
        }
        return Result.error("Usage: replay <status|job <job-id>|delete <job-id> confirm>");
    }

    private Result cache(ConsoleInputParser.ParsedInput input) {
        if (input.size() == 4 && List.of("query", "delete", "get", "fetch")
                .contains(input.value(1).toLowerCase(Locale.ROOT))) {
            String type = cacheControlType(input.value(2));
            long id = positiveLong(input.value(3));
            return Result.ok(formatCacheControl(access.controlCache(new CacheControlRequest(
                    input.value(1), type, id
            ))));
        }
        if (input.size() == 2 && "status".equalsIgnoreCase(input.value(1))) {
            CacheService.CacheSummary value = access.status().cache();
            return Result.ok("""
                    Cache usage
                      Beatmaps: %d files, %s
                      Images: %d files, %s
                      Replays: %d files, %s
                      Score JSON: %d files, %s
                      Beatmapsets: %d files, %s
                      Total: %d files, %s
                    """.formatted(value.beatmaps().files(), bytes(value.beatmaps().bytes()),
                    value.images().files(), bytes(value.images().bytes()), value.replays().files(), bytes(value.replays().bytes()),
                    value.scoreJson().files(), bytes(value.scoreJson().bytes()), value.beatmapsets().files(),
                    bytes(value.beatmapsets().bytes()), value.totalFiles(), bytes(value.totalBytes())).stripTrailing());
        }
        if (input.size() == 4 && "clear".equalsIgnoreCase(input.value(1))
                && "confirm".equalsIgnoreCase(input.value(3))) {
            CacheService.CacheArea area = cacheArea(input.value(2));
            return Result.ok("Removed " + access.clearCache(area) + " cache entries.");
        }
        return Result.error("Usage: cache <query|delete|get|fetch> <score|beatmap|beatmapset|replay> <id> | cache status | cache clear <area> confirm");
    }

    private Result config(ConsoleInputParser.ParsedInput input) {
        if (input.size() != 2) return Result.error("Usage: config <show|check>");
        if ("check".equalsIgnoreCase(input.value(1))) {
            ConfigLoader.loadConfig();
            return Result.ok("config.yml is valid. No settings were applied.");
        }
        if (!"show".equalsIgnoreCase(input.value(1))) return Result.error("Usage: config <show|check>");
        return Result.ok("""
                Effective configuration (credentials redacted)
                  webserver.port = %d
                  webserver.maxThreads = %d
                  webserver.minThreads = %d
                  webserver.idleTimeout = %d
                  ostella.requestPerSecond = %d
                  ostella.replayRequestIntervalMillis = %d
                  ostella.replayMaxConcurrent = %d
                  ostella.renderWorkers = %d
                  ostella.debugMode = %s
                  ostella.safeFlags = %s
                  osu.clientId = %s
                  osu.clientSecret = configured (redacted)
                  replayRender.enabled = %s
                  replayRender.workers = %s
                  replayRender.apiKey = %s
                  performancePlus.endpoint = %s
                """.formatted(config.webserver().port(), config.webserver().maxThreads(), config.webserver().minThreads(),
                config.webserver().idleTimeout(), config.ostella().requestPerSecond(),
                config.ostella().replayRequestIntervalMillis(), config.ostella().replayMaxConcurrent(),
                config.ostella().renderWorkers(), config.ostella().debugMode(), config.ostella().safeFlags(),
                config.osu().clientId(), config.replayRender().enabled(),
                String.join(", ", config.replayRender().workers()),
                config.replayRender().apiKey().isBlank() ? "not set" : "configured (redacted)",
                config.performancePlus().endpoint().isBlank()
                        ? "not set"
                        : config.performancePlus().endpoint()).stripTrailing());
    }

    private Result log(ConsoleInputParser.ParsedInput input) {
        if (input.size() == 2 && "show".equalsIgnoreCase(input.value(1)))
            return Result.ok("Root log level: " + LogManager.getRootLogger().getLevel());
        if (input.size() == 3 && "level".equalsIgnoreCase(input.value(1))) {
            Level level = level(input.value(2)); Configurator.setRootLevel(level);
            return Result.ok("Root log level changed to " + level + ".");
        }
        return Result.error("Usage: log <show|level <trace|debug|info|warn|error>>");
    }

    private Result system() {
        Runtime runtime = Runtime.getRuntime(); long used = runtime.totalMemory() - runtime.freeMemory();
        return Result.ok("""
                System information
                  oStella: %s
                  Java: %s (%s)
                  OS: %s %s
                  Processors: %d
                  Threads: %d
                  Heap: %s used / %s max
                  Uptime: %s
                """.formatted(VersionInfo.getVersion(), System.getProperty("java.version"), System.getProperty("java.vendor"),
                System.getProperty("os.name"), System.getProperty("os.arch"), runtime.availableProcessors(),
                Thread.getAllStackTraces().size(), bytes(used), bytes(runtime.maxMemory()),
                duration(System.currentTimeMillis() - STARTED_AT)).stripTrailing());
    }

    private Result stop(ConsoleInputParser.ParsedInput input) {
        if (input.size() != 2 || !"confirm".equalsIgnoreCase(input.value(1))) return Result.error("Usage: stop confirm");
        CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(access::requestStop);
        return Result.ok("Graceful shutdown requested.");
    }

    private static Result exact(ConsoleInputParser.ParsedInput input, int size,
                                java.util.function.Supplier<Result> action, String usage) {
        return input.size() == size ? action.get() : Result.error(usage);
    }

    private static String formatReplayJob(String id, ReplayService.JobProgress job) {
        StringBuilder value = new StringBuilder(id).append(" | ").append(job.status());
        if (job.progress() != null) value.append(" | ").append(job.progress());
        if (job.speed() != null) value.append(" | ").append(job.speed());
        if (job.eta() != null) value.append(" | ETA ").append(job.eta());
        if (job.error() != null) value.append(" | error: ").append(job.error());
        if (job.qqFile() != null) value.append(" | QQ uploaded");
        return value.toString();
    }

    private static CacheService.CacheArea cacheArea(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "beatmap", "beatmaps" -> CacheService.CacheArea.BEATMAPS;
            case "image", "images" -> CacheService.CacheArea.IMAGES;
            case "replay", "replays" -> CacheService.CacheArea.REPLAYS;
            case "score-json", "scores" -> CacheService.CacheArea.SCORE_JSON;
            case "beatmapset", "beatmapsets" -> CacheService.CacheArea.BEATMAPSETS;
            case "all" -> CacheService.CacheArea.ALL;
            default -> throw new IllegalArgumentException("Cache area must be beatmaps, images, replays, score-json, beatmapsets, or all.");
        };
    }

    private static String cacheControlType(String value) {
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!List.of("SCORE", "BEATMAP", "BEATMAPSET", "REPLAY").contains(normalized)) {
            throw new IllegalArgumentException("Cache type must be score, beatmap, beatmapset, or replay.");
        }
        return normalized;
    }

    private static long positiveLong(String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException("ID must be a positive integer.");
    }

    private static String formatCacheControl(CacheControlResult result) {
        StringBuilder output = new StringBuilder(result.operation().toLowerCase(Locale.ROOT))
                .append(' ').append(result.type().toLowerCase(Locale.ROOT)).append(' ').append(result.id());
        for (CacheControlResult.CacheNodeResult node : result.nodes()) {
            output.append("\n  ").append(node.node()).append(": ").append(node.status());
            if (node.path() != null) output.append(" | path=").append(node.path());
            if (node.sizeBytes() != null) output.append(" | size=").append(bytes(node.sizeBytes()));
            if (node.modifiedAt() != null) output.append(" | modified=").append(node.modifiedAt());
            if (node.message() != null) output.append(" | ").append(node.message());
        }
        return output.toString();
    }

    private static String uuid(String value) {
        try { return UUID.fromString(value).toString(); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Job ID must be a UUID."); }
    }

    private static Level level(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "trace" -> Level.TRACE; case "debug" -> Level.DEBUG; case "info" -> Level.INFO;
            case "warn" -> Level.WARN; case "error" -> Level.ERROR;
            default -> throw new IllegalArgumentException("Log level must be trace, debug, info, warn, or error.");
        };
    }

    private static String bytes(long bytes) {
        double value = bytes; String[] units = {"B", "KiB", "MiB", "GiB", "TiB"}; int unit = 0;
        while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
        return unit == 0 ? bytes + " B" : String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static String duration(long millis) {
        long seconds = Duration.ofMillis(Math.max(0, millis)).toSeconds();
        long days = seconds / 86400, hours = seconds % 86400 / 3600, minutes = seconds % 3600 / 60;
        return days > 0 ? "%dd %02dh %02dm".formatted(days, hours, minutes)
                : hours > 0 ? "%dh %02dm".formatted(hours, minutes)
                : "%dm %02ds".formatted(minutes, seconds % 60);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error; while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record Result(boolean success, String message) {
        static Result ok(String message) { return new Result(true, message); }
        static Result error(String message) { return new Result(false, message); }
    }
}
