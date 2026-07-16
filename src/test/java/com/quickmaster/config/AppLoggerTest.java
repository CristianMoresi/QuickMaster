package com.quickmaster.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AppLoggerTest
{
    @Test
    @DisplayName("Exception logging includes the complete cause chain")
    void throwableTextIncludesNestedCause()
    {
        IOException io = new IOException("sharing violation");
        RuntimeException wrapped = new RuntimeException("WAV read failed", io);

        String text = AppLogger.throwableText(wrapped);

        assertTrue(text.contains("java.lang.RuntimeException: WAV read failed"));
        assertTrue(text.contains("Caused by: java.io.IOException: sharing violation"));
    }
}
