package com.quickmaster.config;

import com.quickmaster.processing.dynamics.macro.LevelerExclusions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class LevelerExclusionStoreTest {
    @TempDir Path root;
    @Test void savesMetadataSeparatelyAndChecksSourceLayout() throws Exception {
        Path audio=root.resolve("song.wav");Files.write(audio,new byte[]{4,5,6});
        String key=LevelerExclusionStore.identity(audio);
        var store=new LevelerExclusionStore(root.resolve("settings"));
        var mask=LevelerExclusions.EMPTY.add(17,800);
        store.save(key,48000,1000,mask);
        assertEquals(mask,store.load(key,48000,1000));
        assertThrows(java.io.IOException.class,()->store.load(key,44100,1000));
        assertArrayEquals(new byte[]{4,5,6},Files.readAllBytes(audio));
        store.save(key,48000,1000,LevelerExclusions.EMPTY);
        assertEquals(LevelerExclusions.EMPTY,store.load(key,48000,1000));
        Files.write(audio,new byte[]{4,5,7});assertNotEquals(key,LevelerExclusionStore.identity(audio));
    }
    @Test void rejectsCorruptMetadataAndUnsafePaths() throws Exception {
        var store=new LevelerExclusionStore(root);
        String key="1".repeat(64);
        Files.writeString(root.resolve(key+".json"),"{broken");
        assertThrows(java.io.IOException.class,()->store.load(key,48000,1000));
        assertThrows(IllegalArgumentException.class,()->store.load("../bad",48000,1000));
        Files.writeString(root.resolve(key+".json"),"{\"version\":1,\"rate\":48000,\"frames\":1000,\"regions\":[{\"start\":7,\"end\":1001}]}");
        assertThrows(java.io.IOException.class,()->store.load(key,48000,1000));
    }
}
