package com.quickmaster.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** File-level export transaction, including metadata before replacement. */
public final class AudioExport {
    private AudioExport() { }

    public static void write(AudioFile output, String source, String destination) throws AudioFileException {
        AtomicAudioWrite.write(destination, stage -> {
            output.save(stage.toString());
            AtomicAudioWrite.checkCancelled();
            MetadataPreserver.preserve(source, stage.toString());
        });
    }

    /** Preflight the entire batch before rendering or overwriting any file. */
    public static List<Path> planBatch(List<Path> sources, Path directory, String extension) throws IOException {
        if (!extension.equals(".wav") && !extension.equals(".mp3"))
            throw new IllegalArgumentException("Unsupported export extension");
        Set<String> names = new HashSet<>();
        List<Path> targets = new ArrayList<>();
        for (Path source : sources) {
            String name = source.getFileName().toString();
            int dot = name.lastIndexOf('.');
            name = (dot > 0 ? name.substring(0, dot) : name) + extension;
            if (!names.add(name.toLowerCase(Locale.ROOT)))
                throw new IOException("Multiple source files would overwrite the same output: " + name);
            Path target = directory.resolve(name).toAbsolutePath().normalize();
            for (Path input : sources) {
                if (target.equals(input.toAbsolutePath().normalize())
                        || (Files.exists(target) && Files.isSameFile(target, input)))
                    throw new IOException("Batch export would replace a source file: " + target
                            + ". Choose a separate destination folder.");
            }
            targets.add(target);
        }
        return List.copyOf(targets);
    }
}
