package com.quickmaster.processing.dynamics.leveler;

import com.quickmaster.processing.dynamics.leveler.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RampAllocatorTest {
    private static final int RATE=48000;
    private static RampRegion region(long a,long b,String id,boolean protectedRegion,double target) {
        return new RampRegion(a,b,id,protectedRegion,target);
    }
    private static RampPlan pair(long length,double speed,double leveling) {
        return new RampAllocator().allocate(new AudioFormat(RATE,1,2*length),
                new RampRegion[]{region(0,length,"a",false,3),region(length,2*length,"b",false,-6)},new ControlState(leveling,speed));
    }
    private static void bits(double expected,double observed){assertEquals(Double.doubleToRawLongBits(expected),Double.doubleToRawLongBits(observed));}
    @Test void twentySevenNormativeCasesRepeatedThreeTimes() {
        double[] speeds={0,.5,1},rhos={.5,1,2},amounts={0,.5,1};
        double[] caps={1,0x1.c2718a1537834p-2,0x1.8618618618619p-3};
        long[][] lengths={{96000,192000,384000},{41569,83138,166277},{18000,36000,72000}};
        for(int s=0;s<3;s++)for(int r=0;r<3;r++)for(double amount:amounts)for(int repeat=0;repeat<3;repeat++) {
            long length=StrictMath.round((rhos[r]*(4*StrictMath.pow(.75/4,speeds[s])))*RATE);
            assertEquals(lengths[s][r],length);
            var plan=pair(length,speeds[s],amount);
            double cap=r<2?0:caps[s],effective=Math.min(amount,cap);
            bits(cap,plan.componentCapAt(0));bits(cap,plan.componentCapAt(1));
            bits(3*effective,plan.targetAt(0));bits(-6*effective==0?0:-6*effective,plan.targetAt(1));
            assertEquals(effective==0,plan.schedule().isUnit());
            assertTrue(plan.schedule().pieceCount()<=6);
            assertInvariants(plan);
        }
        assertNotEquals(Double.doubleToRawLongBits(0x1.c2717456e04bdp-2),Double.doubleToRawLongBits(pair(166277,.5,1).componentCapAt(0)));
    }
    @Test void exactPieceEndpointsAndTargets() {
        endpoints(pair(384000,0,.5),new double[]{0,192000,288000,480000,576000,768000});
        endpoints(pair(384000,0,1),new double[]{0,192000,222000,546000,552000,768000});
        endpoints(pair(166277,.5,1),new double[]{0,0x1.44c27052cac27p16,0x1.73276db6db6dbp16,0x1.cff1492492492p17,0x1.44c28p18});
        endpoints(pair(72000,1,1),new double[]{0,0x1.194p15,0x1.416db6db6db6ep15,0x1.91c9249249249p16,0x1.194p17});
        bits(0x1.51d5278fe9a27p0,pair(166277,.5,1).targetAt(0));
        bits(0x1.2492492492493p-1,pair(72000,1,1).targetAt(0));
    }
    @Test void zeroEndpointAndProtectedComponents() {
        var allocator=new RampAllocator();
        var zero=allocator.allocate(new AudioFormat(RATE,1,768000),new RampRegion[]{region(0,384000,"a",false,0),region(384000,768000,"b",false,3)},new ControlState(1,0));
        endpoints(zero,new double[]{288000,480000,576000,768000});
        var protectedPlan=allocator.allocate(new AudioFormat(RATE,1,1200000),new RampRegion[]{region(0,100000,"intro",true,3),region(100000,1100000,"body",false,3),region(1100000,1200000,"outro",true,-6)},new ControlState(1,.5));
        for(int i=0;i<100000;i+=127)bits(0,protectedPlan.schedule().gainDbAt(i));
        for(int i=1100000;i<1200000;i+=127)bits(0,protectedPlan.schedule().gainDbAt(i));
        assertInvariants(protectedPlan);
    }
    @Test void invalidPartitionIsUnitAndCapacityFailureIsLocal() {
        var a=new RampAllocator();var fmt=new AudioFormat(RATE,1,1000000);
        assertTrue(a.allocate(fmt,new RampRegion[]{region(1,1000000,"gap",false,3)},new ControlState(1,.5)).schedule().isUnit());
        var plan=a.allocate(fmt,new RampRegion[]{region(0,1,"tiny",false,3),region(1,10000,"protected",true,0),region(10000,1000000,"body",false,3)},new ControlState(1,.5));
        bits(0,plan.targetAt(0));assertTrue(plan.targetAt(2)>0);assertEquals(1,plan.capacityZeroComponents());assertInvariants(plan);
    }
    @Test void controlsAndFixedBoostReductionAreMonotonic() {
        double old=0;
        for(double amount:new double[]{0,.1,.3,.5,.7,1}) {var p=pair(166277,.5,amount);assertTrue(p.targetAt(0)>=old);old=p.targetAt(0);}
        old=0;for(double speed:new double[]{0,.2,.4,.6,.8,1}) {var p=pair(166277,speed,1);assertTrue(p.targetAt(0)>=old);old=p.targetAt(0);}
        var original=pair(166277,.5,1);var reduced=new RampAllocator().allocateFixed(original,.25);
        bits(original.targetAt(0)*.25,reduced.targetAt(0));bits(original.targetAt(1),reduced.targetAt(1));assertInvariants(reduced);
    }
    private static void endpoints(RampPlan p,double[] endpoints) {
        assertEquals(endpoints.length-1,p.schedule().pieceCount());
        for(int i=0;i<endpoints.length-1;i++){bits(endpoints[i],p.schedule().pieceAt(i).startFrame());bits(endpoints[i+1],p.schedule().pieceAt(i).endFrame());}
    }
    private static void assertInvariants(RampPlan p) {
        double end=0,lastDb=0;
        for(int i=0;i<p.schedule().pieceCount();i++) {
            var piece=p.schedule().pieceAt(i);assertTrue(piece.startFrame()>=end);assertTrue(piece.endFrame()>piece.startFrame());
            assertTrue(piece.startDb()>=-6&&piece.startDb()<=3&&piece.endDb()>=-6&&piece.endDb()<=3);
            if(piece.startFrame()==end)assertEquals(lastDb,piece.startDb(),1e-12);else bits(0,piece.startDb());
            if(piece.shape()==GainPieceShape.SMOOTHSTEP)assertTrue(1.5*Math.abs(piece.endDb()-piece.startDb())/((piece.endFrame()-piece.startFrame())/RATE)<=2+1e-12);
            end=piece.endFrame();lastDb=piece.endDb();
        }
        bits(0,lastDb);
    }
}
