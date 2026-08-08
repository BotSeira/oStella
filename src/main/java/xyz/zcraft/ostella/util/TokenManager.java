package xyz.zcraft.ostella.util;

import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.ostella.config.AppConfig;
import xyz.zcraft.ostella.data.TokenData;
import xyz.zcraft.ostella.network.OsuAPI;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class TokenManager implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(TokenManager.class);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "token-poller");
        thread.setDaemon(true);
        return thread;
    });

    private final AppConfig conf;
    private final AtomicBoolean closed = new AtomicBoolean();

    @Getter
    private volatile TokenData tokenData;

    public TokenManager(AppConfig conf) {
        this.conf = conf;

        startPolling();
    }

    private void startPolling() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!isValid()) {
                    LOG.info("Token missing or nearing expiration. Initiating background renewal.");
                    renewToken();
                }
            } catch (Exception e) {
                LOG.error("Background token check encountered an error", e);
            }
        }, 10, 60, TimeUnit.SECONDS);
    }

    public boolean isValid() {
        TokenData currentToken = this.tokenData;
        if (currentToken == null) {
            return false;
        }

        long elapsedMillis = System.currentTimeMillis() - currentToken.tokenGrantTime();

        long maxValidMillis = (currentToken.expiresIn() - 300) * 1000L;

        return elapsedMillis < maxValidMillis;
    }

    private synchronized void renewToken() {
        try {
            this.tokenData = OsuAPI.getToken(conf);
            LOG.info("Token renewed, expires in {}", tokenData.expiresIn());
        } catch (Exception e) {
            LOG.error("Failed to fetch new token. Will try again on next polling cycle.", e);
        }
    }

    public void blockUntilValid() {
        if (isValid()) {
            return;
        }

        LOG.info("Startup paused: Waiting for a valid Osu API access token...");

        while (!isValid()) {
            if (closed.get()) {
                throw new IllegalStateException("Token manager has been closed");
            }
            synchronized (this) {
                if (isValid()) {
                    break;
                }

                try {
                    this.tokenData = OsuAPI.getToken(conf);
                    LOG.info("Startup token successfully acquired. Expires in {} seconds.", tokenData.expiresIn());
                    break;
                } catch (Exception e) {
                    LOG.error("Failed to fetch token during startup. Retrying in 5 seconds...", e);
                    try {
                        //noinspection BusyWait
                        Thread.sleep(5000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Startup interrupted while waiting for access token", ie);
                    }
                }
            }
        }
    }

    public boolean requestRenewal() {
        if (closed.get()) {
            return false;
        }
        try {
            scheduler.execute(this::renewToken);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            scheduler.shutdownNow();
        }
    }
}
