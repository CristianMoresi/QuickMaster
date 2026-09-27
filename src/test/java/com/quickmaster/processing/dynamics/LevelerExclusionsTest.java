package com.quickmaster.processing.dynamics;

import com.quickmaster.processing.dynamics.macro.LevelerExclusions;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LevelerExclusionsTest {
    @Test void mergesOverlapAndTouchingButNotGaps() {
        var x=LevelerExclusions.EMPTY.add(20,40).add(10,25).add(40,50).add(70,90);
        assertEquals(List.of(new LevelerExclusions.Region(10,50),new LevelerExclusions.Region(70,90)),x.regions());
        assertThrows(UnsupportedOperationException.class,()->x.regions().clear());
    }
    @Test void cropAndDeletionFollowTheSourceTimeline() {
        var x=LevelerExclusions.EMPTY.add(10,30).add(60,90);
        assertEquals(List.of(new LevelerExclusions.Region(0,10),new LevelerExclusions.Region(40,50)),x.crop(20,70).regions());
        assertEquals(List.of(new LevelerExclusions.Region(10,35)),x.delete(25,80).regions());
        assertEquals(LevelerExclusions.EMPTY,x.crop(100,200));
    }
    @Test void protectionIsExactAndFeathersAreOutside() {
        var x=LevelerExclusions.EMPTY.add(100,200);
        for(int i=100;i<200;i++)assertEquals(0,x.weight(i,20));
        assertEquals(1,x.weight(80,20));assertEquals(.5,x.weight(90,20),1e-12);
        assertEquals(0,x.weight(200,20));assertEquals(.5,x.weight(210,20),1e-12);
        assertEquals(1,x.weight(220,20));
    }
}
