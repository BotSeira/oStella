package xyz.zcraft.ostella.console;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.jline.reader.LineReader;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicBoolean;

final class JLineLogBridge implements AutoCloseable {
    private static final String CONSOLE = "CONSOLE";
    private static final String JLINE = "JLINE_CONSOLE";
    private final LoggerContext context;
    private final LoggerConfig root;
    private final Appender original;
    private final Appender replacement;
    private final Level level;
    private final Filter filter;
    private final AtomicBoolean closed = new AtomicBoolean();

    private JLineLogBridge(LoggerContext context, LoggerConfig root, Appender original,
                           Appender replacement, Level level, Filter filter) {
        this.context = context; this.root = root; this.original = original;
        this.replacement = replacement; this.level = level; this.filter = filter;
    }

    static JLineLogBridge install(LineReader reader) {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration configuration = context.getConfiguration();
        LoggerConfig root = configuration.getRootLogger();
        Appender original = root.getAppenders().get(CONSOLE);
        if (original == null) return new JLineLogBridge(context, root, null, null, null, null);
        AppenderRef reference = root.getAppenderRefs().stream()
                .filter(value -> CONSOLE.equals(value.getRef())).findFirst().orElse(null);
        Level level = reference == null ? null : reference.getLevel();
        Filter filter = reference == null ? null : reference.getFilter();
        Appender replacement = new ReaderAppender(reader, original);
        replacement.start();
        synchronized (root) { root.removeAppender(CONSOLE); root.addAppender(replacement, level, filter); }
        context.updateLoggers();
        return new JLineLogBridge(context, root, original, replacement, level, filter);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true) || original == null) return;
        synchronized (root) { root.removeAppender(JLINE); root.addAppender(original, level, filter); }
        context.updateLoggers();
        replacement.stop();
    }

    private static final class ReaderAppender extends AbstractAppender {
        private final LineReader reader;
        private ReaderAppender(LineReader reader, Appender original) {
            super(JLINE, null, original.getLayout(), original.ignoreExceptions(), Property.EMPTY_ARRAY);
            this.reader = reader;
        }
        @Override public void append(LogEvent event) {
            Serializable rendered = toSerializable(event);
            if (rendered != null) reader.printAbove(rendered.toString());
        }
    }
}
