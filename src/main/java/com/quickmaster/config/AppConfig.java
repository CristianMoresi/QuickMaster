package com.quickmaster.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

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
 * <p>
 * <b>Schema version.</b> The JSON carries a {@link #CONFIG_VERSION}
 * number so that stored values can be migrated when their meaning or
 * their default changes. A file older than the current version keeps
 * every value that is still valid and takes the current default for
 * the rest.
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
    /** Default output directory: the user's desktop; see {@link #desktopDir()}. */
    public static final String DEFAULT_OUTPUT_DIR = desktopDir();
    /**
     * Schema version of the persisted JSON. A file that reports an older version
     * (0 when it predates versioning) has its output directory replaced by the
     * current {@link #DEFAULT_OUTPUT_DIR} on load.
     */
    public static final int CONFIG_VERSION = 1;

    private static AppConfig instance;

    /**
     * Path to the JSON config file currently in use. Not
     * persisted in the JSON itself (it would be circular);
     * stored as a runtime field only.
     */
    private transient String configFilePath = DEFAULT_CONFIG_FILE;

    /* --- Persisted fields --- */

    /** Version of the file this state came from; 0 until {@link #save()} stamps it. */
    private int configVersion         = 0;
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
     * <p>
     * A stored output directory is only adopted when it still
     * exists and comes from the current {@link #CONFIG_VERSION};
     * otherwise {@link #DEFAULT_OUTPUT_DIR} takes over and the
     * migrated file is written back.
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
            boolean outdated = fromDisk.configVersion < CONFIG_VERSION;
            if (!outdated && isExistingDirectory(fromDisk.outputDir))
            {
                this.outputDir = fromDisk.outputDir;
            }
            if (fromDisk.defaultExportFormat != null) this.defaultExportFormat = fromDisk.defaultExportFormat;
            if (outdated) save();
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
        configVersion = CONFIG_VERSION;
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

    /** True when the path is non-empty, syntactically valid and an existing directory. */
    private static boolean isExistingDirectory(String path)
    {
        Path p = toPath(path);
        return p != null && Files.isDirectory(p);
    }

    /**
     * {@link Paths#get} that yields {@code null} instead of throwing, so a blank
     * environment variable or a path illegal on this platform is simply skipped.
     */
    private static Path toPath(String first, String... more)
    {
        if (first == null || first.isBlank()) return null;
        try { return Paths.get(first, more); }
        catch (RuntimeException e) { return null; }
    }

    private static void addIfPresent(List<Path> candidates, Path path)
    {
        if (path != null) candidates.add(path);
    }

    /**
     * The user's desktop directory on this machine: the folder exports, presets and
     * the file browser start from until the user picks another one.
     */
    private static String desktopDir()
    {
        return desktopDir(System.getProperty("os.name", ""),
                          System.getProperty("user.home", "."),
                          System::getenv);
    }

    /**
     * Resolves the desktop directory for a given platform, home directory and
     * environment: {@code %USERPROFILE%\Desktop} or its OneDrive redirection on
     * Windows, {@code ~/Desktop} on macOS, and on Linux the XDG desktop directory
     * (environment first, then the path recorded in {@code user-dirs.dirs}, which
     * is where a renamed or localised folder such as {@code ~/Escritorio} lives).
     * The first candidate that exists wins; when none does, the home directory is
     * used, so the result is always a real directory even on a headless install.
     *
     * @param osName  value of the {@code os.name} system property
     * @param home    value of the {@code user.home} system property
     * @param env     environment variable lookup
     * @return absolute path of the desktop, or of the home directory as a fallback
     */
    static String desktopDir(String osName, String home, UnaryOperator<String> env)
    {
        String os = osName.toLowerCase(java.util.Locale.ROOT);
        List<Path> candidates = new ArrayList<>();
        if (os.contains("win"))
        {
            addIfPresent(candidates, toPath(env.apply("USERPROFILE"), "Desktop"));
            addIfPresent(candidates, toPath(home, "Desktop"));
            for (String oneDrive : new String[] { "OneDrive", "OneDriveConsumer", "OneDriveCommercial" })
            {
                addIfPresent(candidates, toPath(env.apply(oneDrive), "Desktop"));
            }
        }
        else if (os.contains("mac") || os.contains("darwin"))
        {
            addIfPresent(candidates, toPath(home, "Desktop"));
        }
        else
        {
            String xdg = env.apply("XDG_DESKTOP_DIR");
            if (xdg != null && !xdg.isBlank()) addIfPresent(candidates, toPath(expandHome(xdg, home)));
            addIfPresent(candidates, xdgUserDirsDesktop(home, env));
            addIfPresent(candidates, toPath(home, "Desktop"));
        }
        for (Path candidate : candidates)
        {
            if (Files.isDirectory(candidate)) return candidate.toString();
        }
        return home;
    }

    /**
     * Desktop path declared in the XDG user-dirs configuration
     * ({@code $XDG_CONFIG_HOME/user-dirs.dirs}, else {@code ~/.config/user-dirs.dirs}),
     * the file Linux desktops use to record localised or relocated user folders.
     *
     * @return the declared path, or {@code null} if the file is absent or has no entry
     */
    private static Path xdgUserDirsDesktop(String home, UnaryOperator<String> env)
    {
        String configHome = env.apply("XDG_CONFIG_HOME");
        Path file = (configHome != null && !configHome.isBlank())
                ? toPath(configHome, "user-dirs.dirs")
                : toPath(home, ".config", "user-dirs.dirs");
        if (file == null) return null;
        try
        {
            for (String line : Files.readAllLines(file))
            {
                String entry = line.trim();
                if (!entry.startsWith("XDG_DESKTOP_DIR")) continue;
                int open = entry.indexOf('"');
                int close = entry.lastIndexOf('"');
                if (open < 0 || close <= open) continue;
                return toPath(expandHome(entry.substring(open + 1, close), home));
            }
        }
        catch (IOException | RuntimeException e)
        {
            return null;
        }
        return null;
    }

    /** Expands a leading {@code $HOME} or {@code ~} in an XDG-style path. */
    private static String expandHome(String path, String home)
    {
        if (path.startsWith("$HOME")) return home + path.substring("$HOME".length());
        if (path.startsWith("~"))     return home + path.substring(1);
        return path;
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