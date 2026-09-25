package com.quickmaster.processing.dynamics.leveler;
import com.quickmaster.processing.dynamics.leveler.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GainPlannerTest {
    @Test void realP01PlanActsOnInteriorsAndPreservesEdgesWithoutReadingConformanceAsAuthority() {
        var c=MusicalPcmFixture.catalog().stream().filter(v->v.key().equals("P01_ABA_LEVEL")).findFirst().orElseThrow();
        var clip=MusicalPcmFixture.generate(c,48000,2);var observed=new LevelerAnalysisEngine().analyzeShadow(clip.pcm(),clip.format(),new CancellationToken());
        assertNotNull(observed);var plan=new GainPlanner().plan(observed.cache(),new ControlState(1,.5));
        assertTrue(plan.schedule().gainDbAt(56*48000)<-.8);assertTrue(plan.schedule().gainDbAt(104*48000)>.8);
        assertEquals(0,plan.schedule().gainDbAt(5*48000));assertEquals(0,plan.schedule().gainDbAt(130*48000));
        assertTrue(new GainPlanner().plan(observed.cache(),new ControlState(0,.5)).schedule().isUnit());
    }
    @Test void controlsRejectNonfiniteAndClampFiniteInputs() {
        assertThrows(IllegalArgumentException.class,()->new ControlState(Double.NaN,.5));
        assertThrows(IllegalArgumentException.class,()->new ControlState(.5,Double.POSITIVE_INFINITY));
        var controls=new ControlState(-1,2);assertEquals(0,controls.leveling());assertEquals(1,controls.speed());
    }
    @Test void rampPlanRejectsUnboundOrNonfiniteRegionMetadata() {
        var format=new AudioFormat(48000,1,4);var controls=new ControlState(1,.5);
        var unit=com.quickmaster.processing.dynamics.SparseGainSchedule.unit(48000,4);
        RampRegion[] region={new RampRegion(0,4,"a",false,3)};
        assertThrows(IllegalArgumentException.class,()->new RampPlan(unit,new AudioFormat(44100,1,4),region,new double[]{0},new double[]{1},controls,0,true));
        assertThrows(IllegalArgumentException.class,()->new RampPlan(unit,format,region,new double[]{Double.NaN},new double[]{1},controls,0,true));
        assertThrows(IllegalArgumentException.class,()->new RampPlan(unit,format,new RampRegion[]{null},new double[]{0},new double[]{1},controls,0,true));
        assertThrows(IllegalArgumentException.class,()->new RampPlan(unit,format,new RampRegion[]{new RampRegion(0,4,"p",true,0)},new double[]{1},new double[]{0},controls,0,true));
    }
}
