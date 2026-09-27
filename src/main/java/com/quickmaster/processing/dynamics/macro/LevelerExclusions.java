package com.quickmaster.processing.dynamics.macro;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Immutable source-frame intervals, start inclusive / end exclusive. Not a chain preset. */
public final class LevelerExclusions {
    public record Region(long start, long end) {
        public Region { if (start < 0 || end <= start) throw new IllegalArgumentException("Invalid exclusion interval"); }
    }
    public static final LevelerExclusions EMPTY = new LevelerExclusions(List.of());
    private final List<Region> regions;
    private LevelerExclusions(List<Region> regions) { this.regions = List.copyOf(regions); }
    public static LevelerExclusions of(List<Region> regions) {
        var sorted = new ArrayList<>(regions); sorted.sort(Comparator.comparingLong(Region::start));
        var merged = new ArrayList<Region>();
        for (var next : sorted) {
            if (!merged.isEmpty() && next.start() <= merged.get(merged.size()-1).end()) {
                var previous = merged.remove(merged.size()-1);
                next = new Region(previous.start(), Math.max(previous.end(), next.end()));
            }
            merged.add(next);
        }
        if (merged.size() > 256) throw new IllegalArgumentException("At most 256 exclusion regions");
        return merged.isEmpty() ? EMPTY : new LevelerExclusions(merged);
    }
    public List<Region> regions() { return regions; }
    public LevelerExclusions atRate(int sourceRate,int destinationRate) {
        if(sourceRate<=0||sourceRate==destinationRate)return this;
        return of(regions.stream().map(r->new Region(Math.round(r.start()*(double)destinationRate/sourceRate),
                Math.max(Math.round(r.start()*(double)destinationRate/sourceRate)+1,Math.round(r.end()*(double)destinationRate/sourceRate)))).toList());
    }
    public LevelerExclusions add(long start, long end) {
        if (end <= start) return this;
        var copy = new ArrayList<>(regions); copy.add(new Region(start,end)); return of(copy);
    }
    public LevelerExclusions remove(int index) {
        var copy = new ArrayList<>(regions); copy.remove(index); return of(copy);
    }
    public LevelerExclusions crop(long start, long end) {
        var copy = new ArrayList<Region>();
        for (var r : regions) {
            long a = Math.max(start,r.start()), b = Math.min(end,r.end());
            if (b > a) copy.add(new Region(a-start,b-start));
        }
        return of(copy);
    }
    public LevelerExclusions delete(long start, long end) {
        if (start < 0 || end <= start) throw new IllegalArgumentException("Invalid deletion");
        var copy = new ArrayList<Region>();
        for (var r : regions) {
            long a = r.start() <= start ? r.start() : Math.max(start,r.start()-(end-start));
            long b = r.end() <= start ? r.end() : Math.max(start,r.end()-(end-start));
            if (b > a) copy.add(new Region(a,b));
        }
        return of(copy);
    }
    /** Smoothstep feathers lie OUTSIDE the protected interval, never inside it. */
    public double weight(double frame, double featherFrames) {
        return weight(frame,featherFrames,0);
    }
    public double weight(double frame, double featherFrames, double guardFrames) {
        int lo=0,hi=regions.size();
        while(lo<hi) { int m=(lo+hi)>>>1; if(regions.get(m).end()<=frame)lo=m+1;else hi=m; }
        double distance=Double.POSITIVE_INFINITY;
        if(lo<regions.size()) {
            var next=regions.get(lo);
            if(frame>=next.start())return 0;
            distance=next.start()-frame;
        }
        if(lo>0)distance=Math.min(distance,frame-regions.get(lo-1).end());
        double t=Math.max(0,Math.min(1,(distance-guardFrames)/Math.max(1,featherFrames)));
        return t*t*(3-2*t);
    }
    @Override public boolean equals(Object o) { return o instanceof LevelerExclusions e && regions.equals(e.regions); }
    @Override public int hashCode() { return regions.hashCode(); }
}
