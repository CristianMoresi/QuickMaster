package com.quickmaster.processing.dynamics.leveler;

import com.quickmaster.processing.dynamics.SparseGainSchedule;
import com.quickmaster.processing.dynamics.leveler.model.*;
import java.util.ArrayList;

/** ADR-003's deterministic total allocation, with one common amount per modifiable component. */
public final class RampAllocator {
    public RampPlan allocate(AudioFormat format,RampRegion[] input,ControlState controls) {
        if(format==null||controls==null)throw new IllegalArgumentException("Format and controls required.");
        RampRegion[] regions=ordered(input,format.frames());
        if(regions==null)return unit(format,controls,false);
        double[] targets=new double[regions.length],caps=new double[regions.length];
        int zero=0;
        for(int from=0;from<regions.length;) {
            if(regions[from].protectedRegion()){from++;continue;}
            int to=from+1;while(to<regions.length&&!regions[to].protectedRegion())to++;
            double cap=capacity(regions,from,to,format.sampleRateHz(),controls.speed());
            if(cap==0&&active(regions,from,to))zero++;
            double amount=Math.min(controls.leveling(),cap);
            for(int i=from;i<to;i++){targets[i]=clamp(amount*regions[i].weightedDb());caps[i]=cap;}
            from=to;
        }
        return emit(format,regions,targets,caps,controls,zero);
    }

    /** Only decreases positive targets of the already feasible plan; preserves all cuts. */
    public RampPlan allocateFixed(RampPlan plan,double positiveScale) {
        if(plan==null||!Double.isFinite(positiveScale)||positiveScale<0||positiveScale>1)throw new IllegalArgumentException("Valid plan and boost scale required.");
        if(!plan.valid())return unit(plan.format(),plan.controls(),false);
        double[] targets=new double[plan.regionCount()],caps=new double[targets.length];
        for(int i=0;i<targets.length;i++){double db=plan.targetAt(i);targets[i]=db>0?db*positiveScale:db;caps[i]=plan.componentCapAt(i);}
        return emit(plan.format(),plan.regions(),targets,caps,plan.controls(),plan.capacityZeroComponents());
    }

    private static RampRegion[] ordered(RampRegion[] input,long frames) {
        if(input==null||input.length==0||input.length>64)return null;
        RampRegion[] result=input.clone();
        for(RampRegion region:result)if(region==null)return null;
        for(int i=1;i<result.length;i++) {
            RampRegion value=result[i];int at=i;
            while(at>0&&before(value,result[at-1])){result[at]=result[at-1];at--;}
            result[at]=value;
        }
        long end=0;
        for(RampRegion region:result){if(region.startFrame()!=end||region.endFrame()>frames)return null;end=region.endFrame();}
        return end==frames?result:null;
    }
    private static boolean before(RampRegion a,RampRegion b) {
        return a.startFrame()<b.startFrame()||(a.startFrame()==b.startFrame()&&(a.endFrame()<b.endFrame()
                ||(a.endFrame()==b.endFrame()&&a.id().compareTo(b.id())<0)));
    }
    private static boolean active(RampRegion[] regions,int from,int to) {
        for(int i=from;i<to;i++)if(regions[i].weightedDb()!=0)return true;
        return false;
    }
    private static double capacity(RampRegion[] regions,int from,int to,int rate,double speed) {
        double[] q=new double[to-from],reserve=new double[q.length+1];
        if(!fits(regions,from,to,rate,speed,0,q,reserve))return 0;
        if(fits(regions,from,to,rate,speed,1,q,reserve))return 1;
        double lo=0,hi=1;
        for(int iteration=0;iteration<60;iteration++) {
            double mid=lo+(hi-lo)*.5;
            if(fits(regions,from,to,rate,speed,mid,q,reserve))lo=mid;else hi=mid;
        }
        return lo;
    }
    private static boolean fits(RampRegion[] regions,int from,int to,int rate,double speed,double amount,double[] q,double[] reserve) {
        for(int i=from;i<to;i++)q[i-from]=clamp(amount*regions[i].weightedDb());
        reservations(regions,from,to,q,speed,reserve);
        return fitsReservations(regions,from,to,rate,reserve);
    }
    private static void reservations(RampRegion[] regions,int from,int to,double[] q,double speed,double[] reserve) {
        double time=4*StrictMath.pow(.75/4,speed);int count=to-from;
        reserve[0]=regions[from].weightedDb()==0?0:Math.max(time,.75*StrictMath.abs(q[0]));
        for(int i=1;i<count;i++)reserve[i]=regions[from+i-1].weightedDb()==regions[from+i].weightedDb()?0:
                Math.max(time,.75*(StrictMath.abs(q[i-1])+StrictMath.abs(q[i])));
        reserve[count]=regions[to-1].weightedDb()==0?0:Math.max(time,.75*StrictMath.abs(q[count-1]));
    }
    private static boolean fitsReservations(RampRegion[] regions,int from,int to,int rate,double[] reserve) {
        int count=to-from;
        for(int i=0;i<count;i++) {
            double need=rate*((i==0?reserve[0]:reserve[i]/2)+(i==count-1?reserve[count]:reserve[i+1]/2));
            if(!(need<=regions[from+i].endFrame()-regions[from+i].startFrame()))return false;
        }
        return true;
    }
    private static RampPlan emit(AudioFormat format,RampRegion[] regions,double[] targets,double[] caps,ControlState controls,int zeros) {
        ArrayList<GainPiece> pieces=new ArrayList<>();
        for(int from=0;from<regions.length;) {
            if(regions[from].protectedRegion()){from++;continue;}
            int to=from+1;while(to<regions.length&&!regions[to].protectedRegion())to++;
            int count=to-from;double[] q=new double[count],reserve=new double[count+1];boolean nonzero=false;
            for(int i=0;i<count;i++){q[i]=targets[from+i];if(q[i]!=0)nonzero=true;}
            if(!nonzero){from=to;continue;}
            reservations(regions,from,to,q,controls.speed(),reserve);
            ArrayList<GainPiece> component=new ArrayList<>();boolean valid=fitsReservations(regions,from,to,format.sampleRateHz(),reserve);
            try {
                if(valid) {
                    double start=regions[from].startFrame();
                    double cursor=start+reserve[0]*format.sampleRateHz();
                    add(component,start,cursor,0,q[0]);
                    for(int i=0;i<count;i++) {
                        double boundary=regions[from+i].endFrame();
                        double nextStart=i==count-1?boundary-reserve[count]*format.sampleRateHz():boundary-reserve[i+1]*format.sampleRateHz()/2;
                        double nextEnd=i==count-1?boundary:boundary+reserve[i+1]*format.sampleRateHz()/2;
                        add(component,cursor,nextStart,q[i],q[i]);
                        add(component,nextStart,nextEnd,q[i],i==count-1?0:q[i+1]);
                        cursor=nextEnd;
                    }
                    valid=postcheck(component,format.sampleRateHz());
                }
            } catch(IllegalArgumentException ex){valid=false;}
            if(valid)pieces.addAll(component);
            else {for(int i=from;i<to;i++){targets[i]=0;caps[i]=0;}zeros++;}
            from=to;
        }
        if(pieces.size()>3*regions.length||pieces.size()>258)return unit(format,controls,false);
        return new RampPlan(new SparseGainSchedule(format.sampleRateHz(),format.frames(),pieces.toArray(new GainPiece[0])),
                format,regions,targets,caps,controls,zeros,true);
    }
    private static void add(ArrayList<GainPiece> output,double start,double end,double first,double last) {
        if(end<start)throw new IllegalArgumentException("Overlapping ramps.");
        if(end==start||first==0&&last==0)return;
        GainPieceShape shape=first==last?GainPieceShape.HOLD:GainPieceShape.SMOOTHSTEP;
        if(shape==GainPieceShape.HOLD&&!output.isEmpty()) {
            GainPiece previous=output.get(output.size()-1);
            if(previous.shape()==GainPieceShape.HOLD&&previous.endFrame()==start&&StrictMath.abs(previous.endDb()-first)<=1e-12) {
                output.set(output.size()-1,new GainPiece(previous.startFrame(),end,previous.startDb(),previous.startDb(),GainPieceShape.HOLD));return;
            }
        }
        output.add(new GainPiece(start,end,first,last,shape));
    }
    private static boolean postcheck(ArrayList<GainPiece> pieces,int rate) {
        double end=0,gain=0;
        for(GainPiece piece:pieces) {
            if(piece.startFrame()<end)return false;
            if(piece.startFrame()==end?StrictMath.abs(piece.startDb()-gain)>1e-12:piece.startDb()!=0)return false;
            if(piece.shape()==GainPieceShape.SMOOTHSTEP&&1.5*StrictMath.abs(piece.endDb()-piece.startDb())/((piece.endFrame()-piece.startFrame())/rate)>2+1e-12)return false;
            end=piece.endFrame();gain=piece.endDb();
        }
        return gain==0;
    }
    private static double clamp(double value){double q=Math.max(-6,Math.min(3,value));return q==0?0:q;}
    private static RampPlan unit(AudioFormat format,ControlState controls,boolean valid) {
        return new RampPlan(SparseGainSchedule.unit(format.sampleRateHz(),format.frames()),format,new RampRegion[0],new double[0],new double[0],controls,0,valid);
    }
}
