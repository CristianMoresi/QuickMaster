package com.quickmaster.processing.dynamics.leveler.model;
import com.quickmaster.processing.dynamics.SparseGainSchedule;

/** Feasible schedule plus bounded source-region targets needed to reduce boosts. No PCM. */
public final class RampPlan {
    private final SparseGainSchedule schedule;
    private final AudioFormat format;
    private final RampRegion[] regions;
    private final double[] targets,caps;
    private final ControlState controls;
    private final int capacityZeroComponents;
    private final boolean valid;
    public RampPlan(SparseGainSchedule schedule,AudioFormat format,RampRegion[] regions,double[] targets,
                    double[] caps,ControlState controls,int capacityZeroComponents,boolean valid) {
        if(schedule==null||format==null||regions==null||targets==null||caps==null||controls==null||regions.length!=targets.length||regions.length!=caps.length
                ||regions.length>64||capacityZeroComponents<0||capacityZeroComponents>regions.length
                ||schedule.sourceRateHz()!=format.sampleRateHz()||schedule.sourceFrames()!=format.frames())throw new IllegalArgumentException("Invalid ramp plan.");
        long end=0;
        for(int i=0;i<regions.length;i++) {
            RampRegion region=regions[i];
            if(region==null||region.startFrame()!=end||region.endFrame()>format.frames()||!Double.isFinite(targets[i])||targets[i]<-6||targets[i]>3
                    ||!Double.isFinite(caps[i])||caps[i]<0||caps[i]>1||(region.protectedRegion()&&(targets[i]!=0||caps[i]!=0)))
                throw new IllegalArgumentException("Invalid source-region metadata.");
            end=region.endFrame();
        }
        if((valid&&end!=format.frames())||(!valid&&(!schedule.isUnit()||regions.length!=0)))throw new IllegalArgumentException("Unbound ramp plan.");
        this.schedule=schedule;this.format=format;this.regions=regions.clone();this.targets=targets.clone();this.caps=caps.clone();
        this.controls=controls;this.capacityZeroComponents=capacityZeroComponents;this.valid=valid;
    }
    public SparseGainSchedule schedule(){return schedule;}
    public AudioFormat format(){return format;}
    public int regionCount(){return regions.length;}
    public RampRegion regionAt(int i){return regions[i];}
    public RampRegion[] regions(){return regions.clone();}
    public double targetAt(int i){return targets[i];}
    public double componentCapAt(int i){return caps[i];}
    public ControlState controls(){return controls;}
    public int capacityZeroComponents(){return capacityZeroComponents;}
    public boolean valid(){return valid;}
}
