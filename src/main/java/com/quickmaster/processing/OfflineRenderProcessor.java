package com.quickmaster.processing;

import com.quickmaster.processing.dynamics.leveler.CancellationToken;
import java.util.function.DoubleConsumer;

/** Optional fused, source-aligned offline analysis/render. Never mutates input.
 * Owns analysis and metering exactly as the ordinary two-phase stage path does.
 */
public interface OfflineRenderProcessor {
    boolean supportsOfflineRender(int samples, int channels);
    float[] renderOffline(float[] input, int channels, DoubleConsumer progress, CancellationToken token);
}
