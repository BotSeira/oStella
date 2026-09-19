package xyz.zcraft.ostella.network;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Tracks queued work as well as direct API calls. Foreground work never waits for prefetch. */
public final class ApiActivity {
    private static int foreground;
    private static long lastActivity = System.nanoTime();
    private static final ThreadLocal<BooleanSupplier> BACKGROUND = new ThreadLocal<>();

    private ApiActivity() {}

    public static synchronized void begin() {
        foreground++;
        lastActivity = System.nanoTime();
    }

    public static synchronized void end() {
        foreground--;
        lastActivity = System.nanoTime();
    }

    public static synchronized boolean idle() {
        return foreground == 0 && System.nanoTime() - lastActivity >= Duration.ofSeconds(5).toNanos();
    }

    public static boolean isBackground() {
        return BACKGROUND.get() != null;
    }

    public static void checkBackground() {
        BooleanSupplier allowed = BACKGROUND.get();
        if (allowed != null && (!allowed.getAsBoolean() || !idle() || Thread.currentThread().isInterrupted())) {
            throw new Yield();
        }
    }

    public static void runBackground(BooleanSupplier allowed, Runnable action) {
        BACKGROUND.set(allowed);
        try {
            checkBackground();
            action.run();
        } finally {
            BACKGROUND.remove();
        }
    }

    public static final class Yield extends RuntimeException {
        public Yield() { super(null, null, false, false); }
    }
}
