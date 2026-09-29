package com.dspark.effects;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClipTransferAuditTest {
    @Test void canonicalClipCurvesMatchIndependentEquations() {
        Clipper clip=new Clipper();
        for(Clipper.Mode mode:Clipper.Mode.values())for(double c:new double[]{1e-9,.1,1,10}) {
            clip.setMode(mode);
            for(int i=-1000;i<=1000;i++) {
                double x=c*i/100;
                double a=Math.abs(x),phi=(1+Math.sqrt(5))/2;
                double expected=switch(mode) {
                    case HARD -> Math.max(-c,Math.min(c,x));
                    case SOFT -> c*Math.tanh(x/c);
                    case ANALOG -> c*Math.sin(Math.max(-Math.PI/2,Math.min(Math.PI/2,x/c)));
                    case GOLDEN_RATIO -> a<=c/phi?x:Math.copySign(c/phi+(c-c/phi)*(a-c/phi)/(a+c-2*c/phi),x);
                };
                assertEquals(expected,clip.shapeSample(x,c),c*2e-14,mode+" input="+x);
            }
        }
    }

    @Test void analogHasUnitSmallSignalSlopeNotPiOverTwo() {
        Clipper clip=new Clipper();clip.setMode(Clipper.Mode.ANALOG);
        assertEquals(1,clip.shapeSample(1e-8,1)/1e-8,1e-12);
    }

    @Test void rationalVoicingsAreContinuousMonotoneAndNeverExpand() {
        Saturation sat=new Saturation();
        for(Saturation.Algorithm mode:Saturation.Algorithm.values()) {
            sat.setAlgorithm(mode);double previous=-Double.MAX_VALUE;
            for(int i=-20000;i<=20000;i++) {
                double x=i/1000.0,y=sat.shapeSample(x,1);
                assertTrue(y>=previous);assertTrue(Math.abs(y)<=Math.abs(x)+1e-15);
                assertTrue(Math.abs(y)<1);previous=y;
                assertEquals(y*1e-8,sat.shapeSample(x*1e-8,1e-8),1e-22);
            }
        }
    }
}
