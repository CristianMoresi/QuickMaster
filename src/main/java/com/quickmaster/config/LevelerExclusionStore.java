package com.quickmaster.config;

import com.google.gson.Gson;
import com.quickmaster.processing.dynamics.macro.LevelerExclusions;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Track metadata in the user's config directory; never writes into source audio. */
public final class LevelerExclusionStore {
    private final Path directory;
    private record Saved(int version,int rate,long frames,List<LevelerExclusions.Region> regions) { }
    public LevelerExclusionStore(Path directory) { this.directory=directory; }
    /** Called by the existing background load worker, not the UI thread. */
    public static String identity(Path audio) throws IOException {
        try {
            var hash=MessageDigest.getInstance("SHA-256");
            try(var in=Files.newInputStream(audio)) {
                byte[] block=new byte[65536];int n;
                while((n=in.read(block))!=-1) {
                    if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
                    hash.update(block,0,n);
                }
            }
            return HexFormat.of().formatHex(hash.digest());
        }catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    private Path path(String identity) {
        if(identity==null||!identity.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid source identity");
        return directory.resolve(identity+".json");
    }
    public LevelerExclusions load(String key,int rate,long frames) throws IOException {
        Path file=path(key);
        if(!Files.exists(file))return LevelerExclusions.EMPTY;
        if(Files.size(file)>65536)throw new IOException("Exclusion metadata is too large");
        try {
            Saved saved=new Gson().fromJson(Files.readString(file),Saved.class);
            if(saved==null||saved.version()!=1||saved.rate()!=rate||saved.frames()!=frames||saved.regions()==null)
                throw new IllegalArgumentException("Source layout mismatch");
            for(var r:saved.regions())if(r==null||r.start()<0||r.end()<=r.start()||r.end()>frames)throw new IllegalArgumentException("Invalid bounds");
            return LevelerExclusions.of(saved.regions());
        }catch(RuntimeException ex){throw new IOException("Invalid exclusion metadata",ex);}
    }
    public void save(String key,int rate,long frames,LevelerExclusions exclusions) throws IOException {
        Path file=path(key);Files.createDirectories(directory);
        Path temp=Files.createTempFile(directory,"regions-",".tmp");
        try {
            Files.writeString(temp,new Gson().toJson(new Saved(1,rate,frames,exclusions.regions())));
            try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException ex){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temp);}
    }
}
