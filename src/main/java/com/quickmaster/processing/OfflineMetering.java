package com.quickmaster.processing;

/** Optional visual history collected by the render worker, never by the audio device. */
public interface OfflineMetering {
    void beginOfflineMetering(int channels);
    void endOfflineMetering();
}
