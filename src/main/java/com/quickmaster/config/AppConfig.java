package com.quickmaster.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Application-wide configuration store, persisted as JSON.
 * <p>
 * Holds user preferences that should survive across sessions:
 * default output directory for exports, default export format,
 * configuration and log file paths, and any future UI
 * settings. Backed by a JSON file written next to the
 * application's working directory; loaded at startup and saved
 * whenever a value changes.
 * <p>
 * <b>Singleton.</b> A single shared instance is exposed via
 * {@link #getInstance()}. Configuration is logically a
 * process-wide resource: every component that reads or writes
 * settings must see the same state, and round-tripping through
 * the disk every time would be wasteful. The Singleton pattern
 * is the natural fit. The constructor is private so no other
 * instance can be created accidentally.
 * <p>
 * <b>Resilience.</b> If the JSON file is missing or
 * corrupted at load time, the configuration falls back to safe
 * default values and immediately writes a fresh file with those
 * defaults. The application never crashes due to an invalid
 * config file.
 * <p>
 * <b>Path resolution.</b> The config and log files live in a
 * per-user application-data directory ({@code %APPDATA%\QuickMaster}
 * on Windows, {@code ~/Library/Application Support/QuickMaster} on
 * macOS, {@code ~/.config/QuickMaster} on Linux), so a portable app
 * folder stays clean and nothing is left behind when the user deletes
 * it. The config path can be overridden via
 * {@link #setConfigFilePath(String)} for testing.
 */
public class AppConfig
{
    /** Per-user application-data directory (created if absent); see {@link #userDataDir()}. */
    public static final String DATA_DIR = userDataDir();
    /** Full path of the JSON config inside the user data directory. */
    public static final String DEFAULT_CONFIG_FILE = Paths.get(DATA_DIR, "quickmaster-config.json").toString();
    /** Full path of the log file inside the user data directory. */
    public static final String DEFAULT_LOG_FILE = Paths.get(DATA_DIR, "quickmaster.log").toString();
    /** Default export format. */
    public static final String DEFAULT_EXPORT_FORMAT = "WAV";
    /** Default output directory (current working directory). */
    public static final String DEFAULT_OUTPUT_DIR = ".";

    private static AppConfig instance;

    /**
     * Path to the JSON config file currently in use. Not
     * persisted in the JSON itself (it would be circular);
     * stored as a runtime field only.
     */
    private transient String configFilePath = DEFAULT_CONFIG_FILE;

    /* --- Persisted fields --- */

    private String outputDir          = DEFAULT_OUTPUT_DIR;
    private String defaultExportFormat = DEFAULT_EXPORT_FORMAT;
    private transient String logFilePath = DEFAULT_LOG_FILE;   // not persisted: always the data-dir default

    /** Private to enforce Singleton access via {@link #getInstance()}. */
    private AppConfig()
    {
        // No-op: defaults are field initialisers.
    }

    /**
     * Returns the single shared configuration instance, creating
     * it on the first call. The first call also attempts to load
     * the JSON file from disk; on failure (missing or malformed)
     * defaults are kept and a fresh file is written.
     *
     * @return the shared AppConfig instance
     */
    public static synchronized AppConfig getInstance()
    {
        if (instance == null)
        {
            instance = new AppConfig();
            instance.load();
        }
        return instance;
    }

    /* --- Getters --- */

    public String getOutputDir()           { return outputDir; }
    public String getDefaultExportFormat() { return defaultExportFormat; }
    public String getLogFilePath()         { return logFilePath; }
    public String getConfigFilePath()      { return configFilePath; }

    /* --- Setters: each one persists immediately --- */

    /**
     * Sets the default output directory used by the export flow
     * and saves the configuration to disk.
     *
     * @param outputDir  directory path; must not be null or empty
     */
    public void setOutputDir(String outputDir)
    {
        requireNonEmpty(outputDir, "outputDir");
        this.outputDir = outputDir;
        save();
    }

    /**
     * Sets the default export format and saves the configuration.
     *
     * @param format  format identifier (e.g. "WAV", "MP3")
     */
    public void setDefaultExportFormat(String format)
    {
        requireNonEmpty(format, "defaultExportFormat");
        this.defaultExportFormat = format;
        save();
    }

    /**
     * Sets the path of the log file and saves the configuration.
     *
     * @param logFilePath  path on disk, relative or absolute
     */
    public void setLogFilePath(String logFilePath)
    {
        requireNonEmpty(logFilePath, "logFilePath");
        this.logFilePath = logFilePath;
        save();
    }

    /**
     * Overrides the JSON config file path. Intended for tests or
     * advanced customisation. Does not move the existing file:
     * the next {@link #save()} will write to the new location.
     *
     * @param configFilePath  new config file path
     */
    public void setConfigFilePath(String configFilePath)
    {
        requireNonEmpty(configFilePath, "configFilePath");
        this.configFilePath = configFilePath;
    }

    /* --- Persistence --- */

    /**
     * Loads the configuration from {@link #configFilePath}. If
     * the file does not exist or contains invalid JSON, the
     * current (default) field values are kept and the file is
     * regenerated by an immediate {@link #save()} call. The
     * application is therefore guaranteed to have a valid,
     * up-to-date config on disk after this method returns.
     */
    public void load()
    {
        Path path = Paths.get(configFilePath);
        if (!Files.exists(path))
        {
            save();
            return;
        }
        try
        {
            String json = Files.readString(path);
            Gson gson = new Gson();
            AppConfig fromDisk = gson.fromJson(json, AppConfig.class);
            if (fromDisk == null)
            {
                save();
                return;
            }
            // Copy persisted fields only; the paths are runtime (data directory).
            if (fromDisk.outputDir != null)           this.outputDir           = fromDisk.outputDir;
            if (fromDisk.defaultExportFormat != null) this.defaultExportFormat = fromDisk.defaultExportFormat;
        }
        catch (IOException | JsonSyntaxException e)
        {
            // Corrupted or unreadable: fall back to defaults and rewrite.
            save();
        }
    }

    /**
     * Writes the current configuration to {@link #configFilePath}
     * as pretty-printed JSON. Errors are swallowed silently
     * because configuration persistence must not crash the
     * application; if the disk is full or read-only, the user
     * keeps working with the in-memory configuration. A future
     * iteration can route this to the logging subsystem.
     */
    public void save()
    {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(this);
        try
        {
            Files.writeString(Paths.get(configFilePath), json);
        }
        catch (IOException e)
        {
            // Intentionally swallowed; see Javadoc.
        }
    }

    /* --- Helpers --- */

    private static void requireNonEmpty(String value, String fieldName)
    {
        if (value == null || value.isBlank())
        {
            throw new IllegalArgumentException(
                    fieldName + " must not be null or empty.");
        }
    }

    /**
     * Per-user application-data directory for QuickMaster, created if needed:
     * {@code %APPDATA%\QuickMaster} on Windows, {@code ~/Library/Application
     * Support/QuickMaster} on macOS, {@code $XDG_CONFIG_HOME} (or
     * {@code ~/.config})/QuickMaster on Linux. Keeps config and logs with the
     * user, not inside the portable application folder.
     */
    private static String userDataDir()
    {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String home = System.getProperty("user.home", ".");
        Path dir;
        if (os.contains("win"))
        {
            String appData = System.getenv("APPDATA");
            Path base = (appData != null && !appData.isBlank()) ? Paths.get(appData) : Paths.get(home);
            dir = base.resolve("QuickMaster");
        }
        else if (os.contains("mac") || os.contains("darwin"))
        {
            dir = Paths.get(home, "Library", "Application Support", "QuickMaster");
        }
        else
        {
            String xdg = System.getenv("XDG_CONFIG_HOME");
            Path base = (xdg != null && !xdg.isBlank()) ? Paths.get(xdg) : Paths.get(home, ".config");
            dir = base.resolve("QuickMaster");
        }
        try { Files.createDirectories(dir); } catch (IOException ignored) { }
        return dir.toString();
    }
}