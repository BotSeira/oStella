package xyz.zcraft.ostella.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.FileTemplateResolver;
import xyz.zcraft.ostella.data.Placement;
import xyz.zcraft.ostella.data.MultiplayerResultData;
import xyz.zcraft.ostella.data.ScoreType;
import xyz.zcraft.ostella.network.controller.AnalyzeController;
import xyz.zcraft.ostella.util.Colors;
import xyz.zcraft.ostella.util.MiscUtil;
import xyz.zcraft.ostella.util.format.*;
import xyz.zcraft.osu.model.*;
import xyz.zcraft.osu.parser.data.beatmap.DiffSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

public class RenderService implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(RenderService.class);
    @Getter
    private final ExecutorService renderExecutor;
    private final ThreadLocal<RenderWorkerState> workerStateLocal =  new ThreadLocal<>();
    private final TemplateEngine templateEngine;
    private final TemplateEngine templateEngineLocal;

    private static final Path LOCAL_TEMPLATES_PATH = Path.of("templates");
    private static final Path LOCAL_ASSETS_PATH = LOCAL_TEMPLATES_PATH.resolve("assets");

    public RenderService(int maxWorkers) {
        if (maxWorkers <= 0) {
            throw new IllegalArgumentException("maxWorkers must be greater than 0: " + maxWorkers);
        }

        ThreadFactory threadFactory = getThreadFactory();

        this.renderExecutor = Executors.newFixedThreadPool(maxWorkers, threadFactory);

        LOG.info("Initializing template resolver");
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setPrefix("/template/"); // Looks in src/main/resources/template/
        resolver.setCacheable(false);
        resolver.setSuffix(".html");

        templateEngine = new TemplateEngine();
        templateEngine.setTemplateResolver(resolver);

        FileTemplateResolver resolverLocal = new FileTemplateResolver();
        resolverLocal.setTemplateMode(TemplateMode.HTML);
        resolverLocal.setSuffix(".html");
        resolverLocal.setCacheable(false);
        resolverLocal.setCheckExistence(true);

        templateEngineLocal = new TemplateEngine();
        templateEngineLocal.setTemplateResolver(resolverLocal);

        LOG.info("Configured {} lazy Playwright render workers", maxWorkers);

        try {
            Files.createDirectories(LOCAL_TEMPLATES_PATH);
            Files.createDirectories(LOCAL_ASSETS_PATH);
        } catch (IOException e) {
            LOG.error("Failed to create local templates or assets directory", e);
        }
    }

    private @NonNull ThreadFactory getThreadFactory() {
        AtomicInteger workerId = new AtomicInteger();

        return executorWorker -> {
            Thread thread = new Thread(() -> {
                try (RenderWorkerState state = RenderWorkerState.create()) {
                    workerStateLocal.set(state);
                    executorWorker.run();
                } finally {
                    workerStateLocal.remove();
                }
            });

            thread.setName("ostella-render-" + workerId.incrementAndGet());

            thread.setUncaughtExceptionHandler((t, error) ->
                    LOG.error(
                            "Uncaught exception in render worker {}",
                            t.getName(),
                            error
                    )
            );

            return thread;
        };
    }

    private byte[] takeScreenshot(String html) {
        RenderWorkerState workerState = workerStateLocal.get();

        if (workerState == null) {
            throw new IllegalStateException("Image rendering must run on RenderService's executor");
        }

        var context = workerState.context();
        
        try (Page page = context.newPage()) {
            page.setContent(html);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            page.waitForFunction("() => Array.from(document.images).every(img => img.complete)");
            return page.locator("body").screenshot();
        }
            
        // page.waitForFunction("""
        // () => document.fonts.status === 'loaded'
        //    && Array.from(document.images).every(img => img.complete)
        //    && (
        //        window.__OSTELLA_RENDER_READY__ === undefined
        //        || window.__OSTELLA_RENDER_READY__ === true
        //    )
        // """);
    }

    private Context createContext() {
        Context ctx = new Context();
        //noinspection InstantiationOfUtilityClass
        ctx.setVariable("Colors", new Colors());
        //noinspection InstantiationOfUtilityClass
        ctx.setVariable("Beatmaps", new BeatmapFormatUtil());
        //noinspection InstantiationOfUtilityClass
        ctx.setVariable("Beatmapsets", new BeatmapsetFormatUtil());
        //noinspection InstantiationOfUtilityClass
        ctx.setVariable("Scores", new ScoreFormatUtil());
        //noinspection InstantiationOfUtilityClass
        ctx.setVariable("Users", new UserFormatUtil());
        //noinspection InstantiationOfUtilityClass
        ctx.setVariable("Mods", new ModFormatUtil());
        //noinspection InstantiationOfUtilityClass
        ctx.setVariable("DiffSpecs", new DiffSpecFormatUtil());
        //noinspection InstantiationOfUtilityClass
        ctx.setVariable("Osu", new OsuFormatUtil());
        //noinspection InstantiationOfUtilityClass
        ctx.setVariable("cache", new CacheService());
        //noinspection InstantiationOfUtilityClass
        ctx.setVariable("MiscUtil", new MiscUtil());
        return ctx;
    }

    public byte[] renderScores(UserExtended user, List<Score> scores, ScoreType type) {
        return renderScores(user, scores, type, List.of());
    }

    public byte[] renderScores(UserExtended user, List<Score> scores, ScoreType type, List<String> filters) {
        return renderScores(
                user,
                scores,
                type,
                filters,
                IntStream.rangeClosed(1, scores.size()).boxed().toList()
        );
    }

    public byte[] renderScores(
            UserExtended user,
            List<Score> scores,
            ScoreType type,
            List<String> filters,
            List<Integer> scorePositions
    ) {
        if (scores.size() != scorePositions.size()) {
            throw new IllegalArgumentException("Each rendered score must have a display position");
        }
        Context ctx = createContext();
        ctx.setVariable("user", user);
        ctx.setVariable("scores", scores);
        ctx.setVariable("scorePositions", List.copyOf(scorePositions));
        ctx.setVariable("filters", List.copyOf(filters));
        ctx.setVariable("filtered", !filters.isEmpty());
        ctx.setVariable("type", filters.isEmpty()
                ? switch (type) {
                    case BEST -> "Best of " + scores.size() + " Scores";
                    case RECENT -> "Most recent " + scores.size() + " Scores";
                    case RECENT_PASS -> "Most recent " + scores.size() + " Passed Scores";
                }
                : switch (type) {
                    case BEST -> "Filtered Scores From Best Scores";
                    case RECENT -> "Filtered Scores From Recent Scores";
                    case RECENT_PASS -> "Filtered Scores From Recent Passed Scores";
                });
        ctx.setVariable("change", UserFormatUtil.getScoreChange(user));
        ctx.setVariable("time", Instant.now().truncatedTo(ChronoUnit.SECONDS));

        String finalHtml = templateEngine.process("score-list", ctx);

        return takeScreenshot(finalHtml);
    }

    public byte[] renderMapLeaderboard(BeatmapExtended map, List<Placement> placements, double ppMax) {
        Context ctx = createContext();
        ctx.setVariable("beatmap", map);
        ctx.setVariable("placements", placements);
        ctx.setVariable("ppMax", ppMax);
        ctx.setVariable("time", Instant.now().truncatedTo(ChronoUnit.SECONDS));

        String finalHtml = templateEngine.process("map-leaderboard", ctx);

        return takeScreenshot(finalHtml);
    }

    public byte[] renderMultiplayerResult(MultiplayerResultData result) {
        Context ctx = createContext();
        ctx.setVariable("result", result);
        ctx.setVariable("time", Instant.now().truncatedTo(ChronoUnit.SECONDS));

        String finalHtml = templateEngine.process("multiplayer-room-result", ctx);
        return takeScreenshot(finalHtml);
    }

    public byte[] renderLeaderboard(List<User> users) {
        Context ctx = createContext();
        ctx.setVariable("users", users);
        ctx.setVariable("time", Instant.now().truncatedTo(ChronoUnit.SECONDS));

        String finalHtml = templateEngine.process("user-leaderboard", ctx);

        return takeScreenshot(finalHtml);
    }

    public byte[] renderBeatmap(BeatmapExtended map, DiffSpec spec, List<Double> diff) {
        Context ctx = createContext();
        ctx.setVariable("beatmap", map);
        ctx.setVariable("diff", spec);
        ctx.setVariable("calDiff", diff);
        ctx.setVariable("time", Instant.now().truncatedTo(ChronoUnit.SECONDS));

        String finalHtml = templateEngine.process("beatmap", ctx);

        return takeScreenshot(finalHtml);
    }

    public byte[] renderScore(Score score, DiffSpec spec, Double calPp, boolean replayPresent) {
        Context ctx = createContext();
        ctx.setVariable("score", score);
        ctx.setVariable("diff", spec);
        ctx.setVariable("ppSafe", score.getPp() == null ? calPp : score.getPp());
        ctx.setVariable("time", Instant.now().truncatedTo(ChronoUnit.SECONDS));
        ctx.setVariable("replayPresent", replayPresent);

        String finalHtml = templateEngine.process("single-score", ctx);

        return takeScreenshot(finalHtml);
    }

    public byte[] renderScoreAnalysis(AnalyzeController.ScoreAnalyzeData analyzeData) {
        Context ctx = createContext();
        ctx.setVariable("score", analyzeData.score());
        ctx.setVariable("diff", analyzeData.diffSpec());
        ctx.setVariable("time", Instant.now().truncatedTo(ChronoUnit.SECONDS));

        ctx.setVariable("hitErrors", analyzeData.hitErrors());
        ctx.setVariable("hitPositions", analyzeData.hitPositions());
        ctx.setVariable("hitPositionsAbsolute", analyzeData.hitPositionsAbsolute());
        ctx.setVariable("missPositions", analyzeData.missPositions());
        ctx.setVariable("missPositionsAbsolute", analyzeData.missPositionsAbsolute());
        ctx.setVariable("aimBias", analyzeData.aimBias());
        ctx.setVariable("avgTimingError", analyzeData.avgTimingError());
        ctx.setVariable("analyze", analyzeData.replayAnalyze());
        ctx.setVariable("performanceData", analyzeData.performanceGraph().windowDifficulties());
        ctx.setVariable("missTimes", analyzeData.performanceGraph().misses());
        ctx.setVariable("hit50Times", analyzeData.performanceGraph().hit50s());
        ctx.setVariable("hit100Times", analyzeData.performanceGraph().hit100s());
        ctx.setVariable("sliderTickBreakTimes", analyzeData.performanceGraph().sliderTickBreaks());
        ctx.setVariable("sliderEndBreakTimes", analyzeData.performanceGraph().sliderEndBreaks());
        ctx.setVariable("mapEndTime", analyzeData.performanceGraph().mapEndTime());

        String finalHtml = templateEngine.process("score-analysis", ctx);

        return takeScreenshot(finalHtml);
    }

    public byte[] renderBeatmapset(Beatmapset beatmapset) {
        beatmapset.getBeatmaps().sort(Comparator.comparingDouble(Beatmap::getDifficultyRating));

        Context ctx = createContext();
        ctx.setVariable("beatmapset", beatmapset);
        ctx.setVariable("time", Instant.now().truncatedTo(ChronoUnit.SECONDS));

        String finalHtml = templateEngine.process("beatmapset", ctx);

        return takeScreenshot(finalHtml);
    }

    public byte[] renderCustomTemplate(String template, Map<String, Object> variables) {
        Context ctx = createContext();
        variables.forEach(ctx::setVariable);
        ctx.setVariable("time", Instant.now().truncatedTo(ChronoUnit.SECONDS));

        String finalHtml = templateEngineLocal.process(
                LOCAL_TEMPLATES_PATH.resolve(template + ".html").toAbsolutePath().toString(),
                ctx
        );

        return takeScreenshot(finalHtml);
    }

    public Status status() {
        if (renderExecutor instanceof ThreadPoolExecutor pool) {
            return new Status(pool.getActiveCount(), pool.getMaximumPoolSize(), pool.getQueue().size(),
                    pool.getCompletedTaskCount());
        }
        return new Status(0, 0, 0, 0);
    }

    @Override
    public void close() {
        LOG.info("Shutting down RenderService executor");
        renderExecutor.close();
    }

    private record RenderWorkerState(
            Playwright playwright,
            Browser browser,
            BrowserContext context
    ) implements AutoCloseable {
        static RenderWorkerState create() {
            Playwright playwright = Playwright.create();

            try {
                Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));

                BrowserContext context = browser.newContext(new Browser.NewContextOptions());

                setupRoutes(context);

                return new RenderWorkerState(playwright, browser, context);
            } catch (Exception e) {
                playwright.close();
                throw e;
            }
        }

        private static void setupRoutes(BrowserContext context) {
            context.route("http://ostella-cache/**", route -> {
                String url = route.request().url();
                String filename = url.substring(url.lastIndexOf('/') + 1);

                Path imagePath = CacheService.getImagePathFromFilename(filename);

                route.fulfill(new Route.FulfillOptions().setPath(imagePath));
            });

            context.route("http://local-asset/**", route -> {
                String url = route.request().url();
                String filename = url.substring("http://local-asset/".length());

                Path imagePath = LOCAL_ASSETS_PATH.resolve(filename);

                route.fulfill(new Route.FulfillOptions().setPath(imagePath));
            });
        }

        private static void closeSafely(String resource, Runnable action) {
            try {
                action.run();
            } catch (Exception e) {
                LOG.error("Error closing {}", resource, e);
            }
        }

        @Override
        public void close() {
//            closeSafely("BrowserContext", context::close);
//            closeSafely("Browser", browser::close);
            closeSafely("Playwright", playwright::close);

            LOG.info(
                    "Playwright closed for render worker {}",
                    Thread.currentThread().getName()
            );
        }
    }

    public record Status(int active, int poolSize, int queued, long completed) {
    }
}
