package xyz.zcraft.ostella;

import lombok.Getter;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import xyz.zcraft.ostella.config.AppConfig;
import xyz.zcraft.ostella.config.ConfigLoader;
import xyz.zcraft.ostella.runtime.OstellaApplication;
import xyz.zcraft.ostella.util.format.UserFormatUtil;

import java.io.IOException;

public class oStella {
    private static final Logger LOG = LogManager.getLogger(oStella.class);
    @Getter
    private static AppConfig conf;

    static void main() {
        LOG.info("Reading config.yml");
        if (!ConfigLoader.configExists()) {
            LOG.warn("Config file does not exist, copying default config. Please check it before restarting.");
            try {
                ConfigLoader.copyDefaultConfig();
            } catch (IOException e) {
                LOG.error("Failed to copy default config", e);
            }
            return;
        }
        try {
            conf = ConfigLoader.loadConfig();
        } catch (RuntimeException e) {
            LOG.error("Invalid configuration. Please check config.yml.", e);
            System.exit(1);
            return;
        }
        if (conf.ostella().debugMode()) {
            Configurator.setRootLevel(Level.DEBUG);
            LOG.warn("Debug mode is enabled. Disable it in production.");
        }
        UserFormatUtil.setSafeFlags(conf.ostella().safeFlags());
        initializeNativeLibraries();

        try (OstellaApplication application = new OstellaApplication(conf)) {
            Thread shutdownHook = new Thread(application::close, "ostella-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            try {
                application.run();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.info("oStella shutdown requested");
        } catch (IOException | RuntimeException e) {
            LOG.error("Failed to run oStella", e);
            System.exit(1);
        }
    }

    private static void initializeNativeLibraries() {
        try {
            LOG.info("Initializing native libraries; startup warnings below may be ignored");
            System.load("");
        } catch (UnsatisfiedLinkError ignored) {
        }
    }
}
