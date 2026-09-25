package com.quickmaster.processing.dynamics.leveler;

import com.quickmaster.processing.dynamics.SparseGainSchedule;
import com.quickmaster.processing.dynamics.leveler.model.*;

/** Full quantized-output proof. Tiles and sample peaks never authorize a schedule. */
public final class TruePeakSafety {
    public SafetyResult constrain(float[] pcm,AudioFormat format,RampPlan plan,CancellationToken cancellation) {
        if(format==null)throw new IllegalArgumentException("A source format is required.");
        SparseGainSchedule unit=SparseGainSchedule.unit(format.sampleRateHz(),format.frames());
        if(cancelled(cancellation))return failure(unit,SafetyStatus.CANCELLED,0,0,0,0);
        if(pcm==null||plan==null||!plan.valid()||pcm.length!=format.frames()*format.channels()
                ||plan.format().sampleRateHz()!=format.sampleRateHz()||plan.format().channels()!=format.channels()||plan.format().frames()!=format.frames())
            return failure(unit,SafetyStatus.INVALID_INPUT,0,0,0,0);
        try {
            // FiniteTruePeakStream validates every sample during the bounded, cancellable input scan.
            double input=scan(pcm,format,unit,cancellation);
            if(cancelled(cancellation))return failure(unit,SafetyStatus.CANCELLED,0,0,0,0);
            double ceiling=Math.min(1,input*StrictMath.exp(.1*StrictMath.log(10.0)/20.0));
            SparseGainSchedule selected=plan.schedule();
            double candidate=scan(pcm,format,selected,cancellation);int passes=1;
            if(cancelled(cancellation))return failure(unit,SafetyStatus.CANCELLED,input,0,ceiling,passes);
            if(candidate<=ceiling)return result(selected,SafetyStatus.PROVEN,input,candidate,ceiling,1,passes);
            RampAllocator allocator=new RampAllocator();
            RampPlan zero=allocator.allocateFixed(plan,0);selected=zero.schedule();
            double safePeak=scan(pcm,format,selected,cancellation);passes++;
            if(cancelled(cancellation))return failure(unit,SafetyStatus.CANCELLED,input,0,ceiling,passes);
            if(safePeak>ceiling) {
                double unitPeak=scan(pcm,format,unit,cancellation);passes++;
                if(cancelled(cancellation))return failure(unit,SafetyStatus.CANCELLED,input,0,ceiling,passes);
                if(unitPeak<=ceiling)return result(unit,SafetyStatus.BOOST_REDUCED,input,unitPeak,ceiling,0,passes);
                boolean protectedViolation=protectedInputExceedsCeiling(pcm,format,plan,cancellation);
                if(cancelled(cancellation))return failure(unit,SafetyStatus.CANCELLED,input,0,ceiling,passes);
                return failure(unit,protectedViolation?SafetyStatus.INFEASIBLE_INPUT_BASELINE:SafetyStatus.PEAK_UNSAFE,input,unitPeak,ceiling,passes);
            }
            double maximumPositive=0;for(int i=0;i<plan.regionCount();i++)maximumPositive=Math.max(maximumPositive,plan.targetAt(i));
            double lo=0,hi=1,best=0;
            for(int iteration=0;iteration<20&&(hi-lo)*maximumPositive>.01;iteration++) {
                double mid=lo+(hi-lo)*.5;
                RampPlan attempt=allocator.allocateFixed(plan,mid);
                double peak=scan(pcm,format,attempt.schedule(),cancellation);passes++;
                if(cancelled(cancellation))return failure(unit,SafetyStatus.CANCELLED,input,0,ceiling,passes);
                if(peak<=ceiling){lo=mid;if(mid>best){best=mid;selected=attempt.schedule();safePeak=peak;}}
                else hi=mid;
            }
            // Quantized interpolation need not be perfectly monotone. The chosen observed-safe plan is reverified.
            candidate=scan(pcm,format,selected,cancellation);passes++;
            if(cancelled(cancellation))return failure(unit,SafetyStatus.CANCELLED,input,0,ceiling,passes);
            if(candidate<=ceiling)return result(selected,SafetyStatus.BOOST_REDUCED,input,candidate,ceiling,best,passes);
            candidate=scan(pcm,format,zero.schedule(),cancellation);passes++;
            if(cancelled(cancellation))return failure(unit,SafetyStatus.CANCELLED,input,0,ceiling,passes);
            if(candidate<=ceiling)return result(zero.schedule(),SafetyStatus.BOOST_REDUCED,input,candidate,ceiling,0,passes);
            candidate=scan(pcm,format,unit,cancellation);passes++;
            if(cancelled(cancellation))return failure(unit,SafetyStatus.CANCELLED,input,0,ceiling,passes);
            return candidate<=ceiling?result(unit,SafetyStatus.BOOST_REDUCED,input,candidate,ceiling,0,passes):failure(unit,SafetyStatus.PEAK_UNSAFE,input,candidate,ceiling,passes);
        } catch(IllegalArgumentException|IllegalStateException|ArithmeticException ex) {
            return failure(unit,cancelled(cancellation)?SafetyStatus.CANCELLED:SafetyStatus.INVALID_INPUT,0,0,0,0);
        }
    }
    private static double scan(float[] pcm,AudioFormat format,SparseGainSchedule schedule,CancellationToken token) {
        FiniteTruePeakStream stream=new FiniteTruePeakStream(format.channels());
        int chunk=(int)Math.min(4096,format.frames());
        float[] rendered=schedule.isUnit()?null:new float[chunk*format.channels()];
        for(long from=0;from<format.frames();from+=chunk) {
            if(cancelled(token))return 0;
            int count=(int)Math.min(chunk,format.frames()-from);
            if(rendered==null)stream.accept(pcm,Math.toIntExact(from),count);
            else {
                for(int frame=0;frame<count;frame++) {
                    if((frame&1023)==0&&cancelled(token))return 0;
                    double db=schedule.gainDbAt(from+frame);
                    double linear=db==0?1:StrictMath.exp(db*StrictMath.log(10.0)/20.0);
                    for(int channel=0;channel<format.channels();channel++) {
                        float original=pcm[Math.toIntExact((from+frame)*format.channels()+channel)];
                        rendered[frame*format.channels()+channel]=db==0?original:(float)(original*linear);
                    }
                }
                stream.accept(rendered,0,count);
            }
        }
        double maximum=stream.finish();
        if(stream.framesAccepted()!=format.frames()||stream.tailFrames()!=6)throw new IllegalStateException("Incomplete finite scan.");
        return maximum;
    }
    private static boolean protectedInputExceedsCeiling(float[] pcm,AudioFormat format,RampPlan plan,CancellationToken token) {
        // A peak whose full 12-tap support is protected cannot be repaired by a modifiable neighbor.
        // Initial and terminal zero padding are protected too; never reset at a region boundary.
        FiniteTruePeakStream stream=new FiniteTruePeakStream(format.channels());
        int regionIndex=0,protectedRun=11;
        for(long frame=0;frame<format.frames();frame++) {
            if((frame&1023)==0&&cancelled(token))return false;
            while(regionIndex+1<plan.regionCount()&&frame>=plan.regionAt(regionIndex).endFrame())regionIndex++;
            boolean protectedFrame=plan.regionCount()>0&&plan.regionAt(regionIndex).protectedRegion();
            protectedRun=protectedFrame?Math.min(12,protectedRun+1):0;
            stream.accept(pcm,Math.toIntExact(frame),1);
            if(protectedFrame)for(int channel=0;channel<format.channels();channel++)
                if(StrictMath.abs(pcm[Math.toIntExact(frame*format.channels()+channel)])>1)return true;
            if(protectedRun==12&&stream.lastFramePeak()>1)return true;
        }
        stream.finish();
        return protectedRun>=11&&stream.tailMaximum()>1;
    }
    private static boolean cancelled(CancellationToken token){return token!=null&&token.isCancelled();}
    private static SafetyResult result(SparseGainSchedule schedule,SafetyStatus status,double input,double output,double ceiling,double scale,int passes) {
        return new SafetyResult(schedule,new SafetyProof(status,input,output,ceiling,scale,passes));
    }
    private static SafetyResult failure(SparseGainSchedule unit,SafetyStatus status,double input,double output,double ceiling,int passes) {
        return result(unit,status,input,output,ceiling,0,passes);
    }
}
