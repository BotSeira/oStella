package xyz.zcraft.ostella.runtime;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.ostella.config.AppConfig;
import xyz.zcraft.ostella.cache.CacheControlRequest;
import xyz.zcraft.ostella.cache.CacheControlResult;
import xyz.zcraft.ostella.console.JLineConsole;
import xyz.zcraft.ostella.console.OstellaConsoleAccess;
import xyz.zcraft.ostella.console.OstellaConsoleProcessor;
import xyz.zcraft.ostella.network.WebServer;
import xyz.zcraft.ostella.service.AsyncService;
import xyz.zcraft.ostella.service.CacheService;
import xyz.zcraft.ostella.service.RenderService;
import xyz.zcraft.ostella.service.ReplayService;
import xyz.zcraft.ostella.util.TokenManager;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public final class OstellaApplication implements AutoCloseable, OstellaConsoleAccess {
    private static final Logger LOG = LogManager.getLogger(OstellaApplication.class);
    private final AppConfig config;
    private final TokenManager tokenManager;
    private final JLineConsole console;
    private final CountDownLatch stopSignal = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile WebServer server;
    private volatile Thread runner;

    public OstellaApplication(AppConfig config) {
        this.config = Objects.requireNonNull(config);
        this.tokenManager = new TokenManager(config);
        this.console = new JLineConsole(new OstellaConsoleProcessor(config, this));
    }

    public void run() throws IOException, InterruptedException {
        runner = Thread.currentThread();
        console.start();
        try {
            tokenManager.blockUntilValid();
        } catch (IllegalStateException e) {
            if (stopSignal.getCount() == 0 && Thread.currentThread().isInterrupted()) return;
            throw e;
        }
        if (stopSignal.getCount() == 0) return;
        WebServer created = new WebServer(config, tokenManager);
        server = created;
        created.start();
        LOG.info("oStella is ready");
        stopSignal.await();
    }

    @Override
    public WebServer.ServerStatus status() {
        WebServer current = server;
        if (current != null) return current.status();
        return new WebServer.ServerStatus(false, 0, 0, tokenManager.isValid(),
                new AsyncService.Status(0, 0, 0, 0, 0),
                new RenderService.Status(0, 0, 0, 0),
                config.replayRender().enabled(), config.replayRender().workers().size(), 0,
                CacheService.summary());
    }

    @Override
    public boolean requestTokenRenewal() {
        return tokenManager.requestRenewal();
    }

    @Override
    public int replayQueueSize() {
        return requireServer().replayQueueSize();
    }

    @Override
    public ReplayService.JobProgress replayJob(String jobId) {
        return requireServer().replayJob(jobId);
    }

    @Override
    public void deleteReplayJob(String jobId) {
        requireServer().deleteReplayJob(jobId);
    }

    @Override
    public int clearCache(CacheService.CacheArea area) {
        return CacheService.clear(area);
    }

    @Override
    public CacheControlResult controlCache(CacheControlRequest request) {
        return requireServer().controlCache(request);
    }

    @Override
    public void requestStop() {
        stopSignal.countDown();
        Thread current = runner;
        if (current != null && current != Thread.currentThread()) current.interrupt();
    }

    private WebServer requireServer() {
        WebServer current = server;
        if (current == null) throw new IllegalStateException("The web server is not running yet");
        return current;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            stopSignal.countDown();
            console.close();
            WebServer current = server;
            if (current != null) current.close();
            tokenManager.close();
        }
    }
}
