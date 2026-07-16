package com.quickmaster.config;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Application-wide logging facade.
 * <p>
 * Wraps {@link java.util.logging.Logger} (the JDK's built-in
 * logging framework) and exposes a small, opinionated API:
 * one logger for the whole application, one log file plus
 * console output, simple severity levels (info, warn, error).
 * <p>
 * The log file path is taken from {@link AppConfig} so the
 * user can override it via configuration. The first call to
 * any logging method initialises the underlying handlers
 * lazily; the constructor is private to enforce static usage.
 * <p>
 * <b>Format.</b> Each line carries a timestamp, the severity
 * level, and the message:
 * <pre>
 *   2026-04-28 14:23:01 INFO  Loaded file: song.wav (44100 Hz, 2 ch, 180.5 s)
 *   2026-04-28 14:23:09 ERROR Save failed: permission denied
 * </pre>
 * Exceptions logged via {@link #error(String, Throwable)}
 * include the stack trace on subsequent lines.
 * <p>
 * <b>Resilience.</b> If the log file cannot be opened (disk
 * full, permission denied, invalid path), logging falls back
 * to console only. The application is never crashed by a
 * logging failure - logging is best-effort by design.
 */
public final class AppLogger
{
    private static final Logger LOGGER = Logger.getLogger("QuickMaster");
    private static boolean initialised = false;

    private AppLogger()
    {
        // Utility class - not instantiable.
    }

    /**
     * Logs an informational message (normal operation).
     *
     * @param message  the message to log
     */
    public static void info(String message)
    {
        ensureInitialised();
        LOGGER.info(message);
    }

    /**
     * Logs a warning (something unexpected happened but the
     * application can continue).
     *
     * @param message  the message to log
     */
    public static void warn(String message)
    {
        ensureInitialised();
        LOGGER.warning(message);
    }

    /**
     * Logs an error (an operation failed).
     *
     * @param message  the message to log
     */
    public static void error(String message)
    {
        ensureInitialised();
        LOGGER.severe(message);
    }

    /**
     * Logs an error with the underlying exception. The stack
     * trace is included in the log file.
     *
     * @param message  the message to log
     * @param cause    the exception that caused the error
     */
    public static void error(String message, Throwable cause)
    {
        ensureInitialised();
        LOGGER.log(Level.SEVERE, message, cause);
    }

    /**
     * Forces (re)initialisation of the logger handlers. Useful
     * if {@link AppConfig#getLogFilePath()} changes during
     * runtime and the new path should take effect.
     */
    public static synchronized void reinitialise()
    {
        initialised = false;
        ensureInitialised();
    }

    /* --- Private helpers --- */

    private static synchronized void ensureInitialised()
    {
        if (initialised)
        {
            return;
        }
        initialised = true;

        // Remove default handlers from the root logger to avoid
        // duplicate console output.
        Logger root = Logger.getLogger("");
        for (Handler h : root.getHandlers())
        {
            root.removeHandler(h);
        }

        LOGGER.setUseParentHandlers(false);
        LOGGER.setLevel(Level.ALL);

        Formatter formatter = new SimpleFormatter();

        // Console handler.
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(Level.WARNING);  // only warn/error to console
        consoleHandler.setFormatter(formatter);
        LOGGER.addHandler(consoleHandler);

        // File handler - fallback to console-only if it fails.
        String path = AppConfig.getInstance().getLogFilePath();
        try
        {
            FileHandler fileHandler = new FileHandler(path, true);  // append
            fileHandler.setLevel(Level.ALL);
            fileHandler.setFormatter(formatter);
            LOGGER.addHandler(fileHandler);
        }
        catch (IOException e)
        {
            LOGGER.warning("Could not open log file '" + path
                    + "'; logging to console only. Cause: " + e.getMessage());
        }
    }

    /**
     * Compact one-line formatter:
     *   2026-04-28 14:23:01 INFO  message text
     */
    private static class SimpleFormatter extends Formatter
    {
        @Override
        public String format(LogRecord record)
        {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%1$tF %1$tT ", record.getMillis()));
            sb.append(String.format("%-5s ", levelLabel(record.getLevel())));
            sb.append(record.getMessage());
            sb.append(System.lineSeparator());
            if (record.getThrown() != null)
            {
                sb.append(throwableText(record.getThrown()));
            }
            return sb.toString();
        }

        private static String levelLabel(Level level)
        {
            if (level == Level.SEVERE)  return "ERROR";
            if (level == Level.WARNING) return "WARN";
            if (level == Level.INFO)    return "INFO";
            return level.getName();
        }
    }

    /**
     * Renders the complete exception tree, including nested causes and
     * suppressed exceptions. Using {@link Throwable#printStackTrace(PrintWriter)}
     * avoids losing the low-level I/O reason when domain exceptions wrap it.
     */
    static String throwableText(Throwable throwable)
    {
        StringWriter text = new StringWriter();
        throwable.printStackTrace(new PrintWriter(text));
        return text.toString();
    }
}
