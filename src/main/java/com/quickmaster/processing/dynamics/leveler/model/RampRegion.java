package com.quickmaster.processing.dynamics.leveler.model;
public final class RampRegion {
    private final long startFrame,endFrame;
    private final String id;
    private final boolean protectedRegion;
    private final double weightedDb;
    public RampRegion(long start,long end,String id,boolean protectedRegion,double weightedDb) {
        if(start<0||end<=start||id==null||id.isEmpty()||!Double.isFinite(weightedDb))throw new IllegalArgumentException("Invalid ramp region.");
        startFrame=start;endFrame=end;this.id=id;this.protectedRegion=protectedRegion;
        this.weightedDb=protectedRegion||weightedDb==0?0:weightedDb;
    }
    public long startFrame(){return startFrame;}
    public long endFrame(){return endFrame;}
    public String id(){return id;}
    public boolean protectedRegion(){return protectedRegion;}
    public double weightedDb(){return weightedDb;}
}
