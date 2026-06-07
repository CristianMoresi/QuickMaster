package com.quickmaster;

/**
 * Plain entry point for packaged (jpackage) builds. Launching a non-Application
 * class lets JavaFX initialize from the classpath, so a packaged app does not
 * need the JavaFX modules on a module path.
 */
public final class Launcher
{
    public static void main(String[] args)
    {
        QuickMasterApp.main(args);
    }
}
