package com.quickmaster.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class JsonFilesTest {
    @TempDir Path dir;
    @Test void writesRoundTripAndLeaveNoStagingFiles() throws Exception {
        Path file = dir.resolve("chain.json");
        JsonFiles.write(file,"{\"title\":\"Canción ♪\"}");
        assertEquals("{\"title\":\"Canción ♪\"}",JsonFiles.read(file));
        JsonFiles.write(file,"{}"); assertEquals("{}",JsonFiles.read(file));
        try(var files=Files.list(dir)) { assertEquals(1,files.count()); }
    }
    @Test void invalidTargetsAndOversizedDocumentsPreserveTheExistingFile() throws Exception {
        Path file = dir.resolve("chain.json"); Files.writeString(file,"old");
        assertThrows(java.io.IOException.class,()->JsonFiles.write(file,"x".repeat(1024*1024+1)));
        assertEquals("old",Files.readString(file));
        assertThrows(java.io.IOException.class,()->JsonFiles.write(dir,"{}"));
        Files.writeString(file,"x".repeat(1024*1024+1));
        assertThrows(java.io.IOException.class,()->JsonFiles.read(file));
        Files.write(file,new byte[]{(byte)0xff});
        assertThrows(java.io.IOException.class,()->JsonFiles.read(file));
    }
    @Test void invalidAndFutureConfigurationsAreNeverOverwrittenOnStartupOrLaterSave() throws Exception {
        for(String content:new String[]{"broken","null","{\"configVersion\":999}"}) {
            var constructor=AppConfig.class.getDeclaredConstructor(); constructor.setAccessible(true);
            AppConfig config=constructor.newInstance();
            Path file=dir.resolve("config.json"); Files.writeString(file,content);
            config.setConfigFilePath(file.toString()); config.load(); config.setDefaultExportFormat("MP3");
            assertEquals(content,Files.readString(file));
        }
    }
}
