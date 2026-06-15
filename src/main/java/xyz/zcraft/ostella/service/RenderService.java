package xyz.zcraft.ostella.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import xyz.zcraft.ostella.data.Placement;
import xyz.zcraft.ostella.data.ScoreType;
import xyz.zcraft.ostella.network.controller.AnalyzeController;
import xyz.zcraft.ostella.util.Colors;
import xyz.zcraft.ostella.util.format.*;
import xyz.zcraft.osu.model.*;
import xyz.zcraft.osu.parser.data.beatmap.DiffSpec;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RenderService implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(RenderService.class);
    @Getter
    private final ExecutorService renderExecutor;
    private final ThreadLocal<Playwright> playwrightLocal = ThreadLocal.withInitial(Playwright::create);
    private final ThreadLocal<Browser> browserLocal = ThreadLocal.withInitial(() -> {
        final Browser browser = playwrightLocal.get().chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        LOG.info("Chromium launched for Thread: {}", Thread.currentThread().getName());
        return browser;
    });
    private final TemplateEngine templateEngine;

    public RenderService(int maxWorkers) {
        if (maxWorkers <= 0) {
            throw new IllegalArgumentException("maxWorkers must be greater than 0: " + maxWorkers);
        }

        this.renderExecutor = Executors.newFixedThreadPool(maxWorkers);

        LOG.info("Initializing template resolver");
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setPrefix("/template/"); // Looks in src/main/resources/template/
        resolver.setSuffix(".html");

        templateEngine = new TemplateEngine();
        templateEngine.setTemplateResolver(resolver);

        LOG.info("Setting up playwright");
    }

    private byte[] takeScreenshot(String html) {
        Browser browser = browserLocal.get();

        try (BrowserContext context = browser.newContext(new Browser.NewContextOptions());
             Page page = context.newPage()) {
            page.route("http://ostella-cache/**", route -> {
                String url = route.request().url();
                String filename = url.substring(url.lastIndexOf("/") + 1);
                java.nio.file.Path imagePath = CacheService.getImagePathFromFilename(filename);
                route.fulfill(new Route.FulfillOptions().setPath(imagePath));
            });

            page.setContent(html);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            page.waitForFunction("() => Array.from(document.images).every(img => img.complete)");
            return page.locator("body").screenshot();
        }
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
        ctx.setVariable("cache", new CacheService());
        return ctx;
    }

    public byte[] renderScores(UserExtended user, List<Score> scores, ScoreType type) {
        Context ctx = createContext();
        ctx.setVariable("user", user);
        ctx.setVariable("scores", scores);
        ctx.setVariable("type", switch (type) {
            case BEST -> "Best of " + scores.size() + " Scores";
            case RECENT -> "Most recent " + scores.size() + " Scores";
            case RECENT_PASS -> "Most recent " + scores.size() + " Passed Scores";
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

    public byte[] renderLeaderboard(List<User> users) {
        Context ctx = createContext();
        ctx.setVariable("users", users);
        ctx.setVariable("time", Instant.now().truncatedTo(ChronoUnit.SECONDS));

        String finalHtml = templateEngine.process("user-leaderboard", ctx);

        return takeScreenshot(finalHtml);
    }

    public byte[] renderBeatmap(BeatmapExtended map, DiffSpec spec) {
        Context ctx = createContext();
        ctx.setVariable("beatmap", map);
        ctx.setVariable("diff", spec);
        ctx.setVariable("time", Instant.now().truncatedTo(ChronoUnit.SECONDS));

        String finalHtml = templateEngine.process("beatmap", ctx);

        return takeScreenshot(finalHtml);
    }

    public byte[] renderScore(Score score, DiffSpec spec, Double calPp) {
        Context ctx = createContext();
        ctx.setVariable("score", score);
        ctx.setVariable("diff", spec);
        ctx.setVariable("ppSafe", score.getPp() == null ? calPp : score.getPp());
        ctx.setVariable("time", Instant.now().truncatedTo(ChronoUnit.SECONDS));

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
        ctx.setVariable("missPositions", analyzeData.missPositions());
        ctx.setVariable("aimBias", analyzeData.aimBias());
        ctx.setVariable("avgTimingError", analyzeData.avgTimingError());
        ctx.setVariable("analyze", analyzeData.replayAnalyze());

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

    @Override
    public void close() {
        LOG.info("Shutting down RenderService executor");
        renderExecutor.close();
    }
}
