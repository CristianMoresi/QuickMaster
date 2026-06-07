package com.quickmaster.audio;

/**
 * Checked exception thrown when an audio file cannot be loaded
 * or saved successfully. Possible causes include:
 * <ul>
 *   <li>The file does not exist or cannot be read from disk.</li>
 *   <li>The file is corrupted or its header is malformed.</li>
 *   <li>The file format, encoding, or bit depth is not supported
 *       by the application.</li>
 *   <li>The destination path cannot be written to.</li>
 * </ul>
 * Callers are expected to catch this exception and present a clear,
 * actionable message to the user, keeping the application in a
 * usable state.
 */
public class AudioFileException extends Exception
{
    /**
     * Creates a new AudioFileException with the given message.
     *
     * @param message  human-readable description of the problem
     */
    public AudioFileException(String message)
    {
        super(message);
    }

    /**
     * Creates a new AudioFileException with the given message and
     * the underlying cause that triggered it. Preserving the cause
     * is important so the full stack trace remains available for
     * debugging and logging.
     *
     * @param message  human-readable description of the problem
     * @param cause    the original exception that triggered this one
     */
    public AudioFileException(String message, Throwable cause)
    {
        super(message, cause);
    }
}