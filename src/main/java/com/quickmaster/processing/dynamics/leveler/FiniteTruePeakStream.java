package com.quickmaster.processing.dynamics.leveler;

import com.dspark.analysis.TruePeak;

/** A fresh per-channel kernel, complete finite extent, and exactly one six-frame EOF tail. */
public final class FiniteTruePeakStream {
    public static final int TAIL_FRAMES=6;
    public static final int MAX_CHUNK_FRAMES=65536;
    private static final double[][] ANNEX2={
        {.001708984375,.010986328125,-.0196533203125,.033203125,-.0594482421875,.1373291015625,.97216796875,-.102294921875,.047607421875,-.026611328125,.014892578125,-.00830078125},
        {-.0291748046875,.029296875,-.0517578125,.089111328125,-.16650390625,.465087890625,.77978515625,-.2003173828125,.1015625,-.0582275390625,.0330810546875,-.0189208984375},
        {-.0189208984375,.0330810546875,-.0582275390625,.1015625,-.2003173828125,.77978515625,.465087890625,-.16650390625,.089111328125,-.0517578125,.029296875,-.0291748046875},
        {-.00830078125,.014892578125,-.026611328125,.047607421875,-.102294921875,.97216796875,.1373291015625,-.0594482421875,.033203125,-.0196533203125,.010986328125,.001708984375}
    };
    private final int channels;
    private final TruePeak[] vendor;
    private final double[][] history;
    private final int[] writePositions;
    private long framesAccepted;
    private int tailFrames;
    private boolean finished,invalid;
    private double maximum,lastFramePeak,tailMaximum;

    public FiniteTruePeakStream(int channels){this(channels,false);}
    public FiniteTruePeakStream(int channels,boolean fallback) {
        if(channels<1||channels>8)throw new IllegalArgumentException("One through eight independent channels required.");
        this.channels=channels;
        vendor=fallback?null:new TruePeak[channels];history=fallback?new double[channels][16]:null;writePositions=fallback?new int[channels]:null;
        if(vendor!=null)for(int c=0;c<channels;c++){vendor[c]=new TruePeak();vendor[c].reset();}
    }
    public void accept(float[] interleaved,int offsetFrames,int countFrames) {
        if(finished||invalid)throw new IllegalStateException("True-peak stream is no longer open.");
        if(interleaved==null||interleaved.length%channels!=0||offsetFrames<0||countFrames<0||countFrames>MAX_CHUNK_FRAMES
                ||(long)offsetFrames+countFrames>interleaved.length/channels){invalid=true;throw new IllegalArgumentException("Invalid complete-frame chunk.");}
        for(int frame=0;frame<countFrames;frame++) {
            lastFramePeak=0;
            int offset=(offsetFrames+frame)*channels;
            for(int c=0;c<channels;c++) {
                double value=interleaved[offset+c];
                if(!Double.isFinite(value)){invalid=true;throw new IllegalArgumentException("Nonfinite input.");}
                observe(c,value);
            }
            framesAccepted++;
        }
    }
    public double finish() {
        if(invalid)throw new IllegalStateException("Invalidated stream has no finite result.");
        if(finished)return maximum;
        for(int frame=0;frame<TAIL_FRAMES;frame++){
            lastFramePeak=0;for(int c=0;c<channels;c++)observe(c,0);
            tailMaximum=Math.max(tailMaximum,lastFramePeak);tailFrames++;
        }
        finished=true;return maximum;
    }
    public long framesAccepted(){return framesAccepted;}
    public int tailFrames(){return tailFrames;}
    public double maximumBeforeFinish(){return maximum;}
    public double lastFramePeak(){return lastFramePeak;}
    public double tailMaximum(){return tailMaximum;}
    private void observe(int channel,double value) {
        double peak;
        if(vendor!=null)peak=vendor[channel].process(value);
        else {
            int position=writePositions[channel];double[] ring=history[channel];ring[position]=value;
            writePositions[channel]=(position+1)&15;peak=StrictMath.abs(value);
            for(int phase=0;phase<4;phase++) {
                double sum=0;int read=position;
                for(int tap=0;tap<12;tap++){sum+=ring[read]*ANNEX2[phase][tap];read=(read-1)&15;}
                double magnitude=StrictMath.abs(sum);if(magnitude>peak)peak=magnitude;
            }
        }
        if(!Double.isFinite(peak)||peak<0){invalid=true;throw new IllegalArgumentException("Nonfinite true peak.");}
        if(peak>lastFramePeak)lastFramePeak=peak;
        if(peak>maximum)maximum=peak;
    }
}
