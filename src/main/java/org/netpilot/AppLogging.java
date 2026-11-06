package org.netpilot;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.TextArea;

/**
 * Sets up file logging and a last-resort handler for unexpected errors.
 *
 * <p>All classes log through {@link System.Logger}; on the module path that is backed by
 * {@code java.util.logging}, which this class configures. Never log passwords or other secrets.
 */
public final class AppLogging {

    private static final System.Logger LOG = System.getLogger(AppLogging.class.getName());

    private static final int MAX_FILE_BYTES = 1_000_000;
    private static final int MAX_FILES = 5;

    private AppLogging() {
    }

    /**
     * Writes logs to {@code <logDirectory>/netpilot-0.log}, rotating across {@value #MAX_FILES} files.
     * Failure to set up file logging is reported on the console but never stops the app.
     */
    public static void configure(Path logDirectory) {
        try {
            Files.createDirectories(logDirectory);
            String pattern = logDirectory.resolve("netpilot-%g.log").toString();
            FileHandler file = new FileHandler(pattern, MAX_FILE_BYTES, MAX_FILES, true);
            file.setEncoding("UTF-8");
            file.setFormatter(new LineFormatter());
            file.setLevel(Level.INFO);

            Logger root = Logger.getLogger("");
            root.addHandler(file);
            root.setLevel(Level.INFO);
        } catch (IOException | SecurityException e) {
            System.err.println("NetPilot: file logging disabled: " + e.getMessage());
        }
    }

    /**
     * Logs any exception that escapes to the top of a thread and shows it to the user, so no
     * error is silently lost.
     */
    public static void installUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            LOG.log(System.Logger.Level.ERROR, "Unexpected error in thread " + thread.getName(), error);
            if (Platform.isFxApplicationThread()) {
                showError(error);
            } else {
                Platform.runLater(() -> showError(error));
            }
        });
    }

    private static void showError(Throwable error) {
        try {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            TextArea details = new TextArea(trace.toString());
            details.setEditable(false);
            details.setWrapText(false);

            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(NetPilotApp.NAME);
            alert.setHeaderText("Something went wrong");
            alert.setContentText(String.valueOf(error.getMessage())
                    + "\n\nDetails were saved to the log file in ~/.netpilot/logs.");
            alert.getDialogPane().setExpandableContent(details);
            alert.show();
        } catch (RuntimeException dialogFailure) {
            // Never throw from the last-resort handler; the error is already logged.
            LOG.log(System.Logger.Level.ERROR, "Could not show error dialog", dialogFailure);
        }
    }

    /** One line per record: {@code 2026-11-06 19:30:51 INFO  Logger - message}. */
    private static final class LineFormatter extends Formatter {

        private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault());

        @Override
        public String format(LogRecord record) {
            StringBuilder line = new StringBuilder()
                    .append(TIME.format(Instant.ofEpochMilli(record.getMillis())))
                    .append(' ')
                    .append(String.format("%-7s", record.getLevel().getName()))
                    .append(' ')
                    .append(shortName(record.getLoggerName()))
                    .append(" - ")
                    .append(formatMessage(record))
                    .append(System.lineSeparator());
            if (record.getThrown() != null) {
                StringWriter trace = new StringWriter();
                record.getThrown().printStackTrace(new PrintWriter(trace));
                line.append(trace);
            }
            return line.toString();
        }

        private static String shortName(String loggerName) {
            if (loggerName == null) {
                return "root";
            }
            int dot = loggerName.lastIndexOf('.');
            return dot < 0 ? loggerName : loggerName.substring(dot + 1);
        }
    }
}
