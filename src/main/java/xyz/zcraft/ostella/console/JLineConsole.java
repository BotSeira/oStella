package xyz.zcraft.ostella.console;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class JLineConsole implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(JLineConsole.class);
    private final OstellaConsoleProcessor processor;
    private final ExecutorService thread = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("ostella-console").factory());
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Terminal terminal;
    private volatile JLineLogBridge bridge;

    public JLineConsole(OstellaConsoleProcessor processor) { this.processor = processor; }
    public void start() { if (running.compareAndSet(false, true)) thread.execute(this::runLoop); }

    private void runLoop() {
        try (Terminal created = TerminalBuilder.builder().system(true).build()) {
            terminal = created;
            Files.createDirectories(Path.of("data"));
            LineReader reader = LineReaderBuilder.builder().appName("oStella").terminal(created)
                    .parser(new DefaultParser()).completer(new CommandCompleter())
                    .variable(LineReader.HISTORY_FILE, Path.of("data", "console-history"))
                    .variable(LineReader.HISTORY_SIZE, 500)
                    .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true).build();
            JLineLogBridge installed = JLineLogBridge.install(reader);
            try (installed) {
                bridge = installed;
                while (running.get()) {
                    try {
                        OstellaConsoleProcessor.Result result = processor.execute(reader.readLine("ostella> "));
                        if (!result.message().isBlank()) reader.printAbove((result.success() ? "" : "Error: ") + result.message());
                    } catch (UserInterruptException ignored) {
                    } catch (EndOfFileException e) { break; }
                }
            } finally { bridge = null; }
        } catch (IOException | RuntimeException e) {
            if (running.get()) LOG.error("Interactive console stopped unexpectedly", e);
        } finally { terminal = null; running.set(false); }
    }

    @Override public void close() {
        running.set(false);
        JLineLogBridge currentBridge = bridge;
        if (currentBridge != null) currentBridge.close();
        Terminal current = terminal;
        if (current != null) try { current.close(); } catch (IOException e) { LOG.warn("Failed to close console terminal", e); }
        thread.shutdownNow();
    }

    private static final class CommandCompleter implements Completer {
        @Override public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
            List<String> values = line.wordIndex() == 0 ? OstellaConsoleProcessor.rootCommands()
                    : line.wordIndex() == 1 && !line.words().isEmpty()
                    ? OstellaConsoleProcessor.subcommands(line.words().getFirst()) : List.of();
            values.forEach(value -> candidates.add(new Candidate(value)));
        }
    }
}
