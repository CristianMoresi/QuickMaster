package com.quickmaster.playback;

/** Session-owned monitor matrix: unity Mid, Side smoothly switched between 1 and 0.
 * Never part of the mastering chain, cache, metering or export. */
final class MonoMonitor {
    private final int fadeFrames;
    private double current, start, target;
    private int position;

    MonoMonitor(int sampleRate, boolean mono) {
        fadeFrames=Math.max(1,(int)Math.round(sampleRate*.020));
        current=start=target=mono?0:1;
        position=fadeFrames;
    }

    void setMono(boolean mono) {
        double next=mono?0:1;
        if(next==target)return;
        start=current;target=next;position=0;
    }

    double nextSideGain() {
        if(position>=fadeFrames)return current;
        double t=++position/(double)fadeFrames;
        current=position==fadeFrames?target:start+(target-start)*t*t*(3-2*t);
        return current;
    }
}
