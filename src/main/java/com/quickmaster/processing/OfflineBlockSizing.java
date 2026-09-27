package com.quickmaster.processing;

/** Optional throughput hint for stages accepting variable offline block sizes.
 * Does not change realtime buffering, latency, analysis or the audio contract. */
public interface OfflineBlockSizing {
    int preferredOfflineBlockFrames();
}
