package com.quickmaster.processing.dynamics.leveler;
import com.quickmaster.processing.dynamics.leveler.model.*;

/** Maps immutable structural evidence to a control-specific, feasible gain plan. */
public final class GainPlanner {
    public RampPlan plan(ShadowAnalysisCache cache,ControlState controls) {
        if(cache==null||controls==null)throw new IllegalArgumentException("Cache and controls required.");
        int count=cache.descriptors().size();RampRegion[] regions=new RampRegion[count];
        for(int i=0;i<count;i++) {
            SegmentDescriptor region=cache.descriptors().get(i);ReferenceTarget target=cache.referencePlan().targets().get(i);
            regions[i]=new RampRegion(region.range().startInclusive(),region.range().endExclusive(),Integer.toString(region.id().ordinal()),
                    cache.protections().get(i).isBlocked(),target.rawDb()*target.gConf());
        }
        return new RampAllocator().allocate(cache.format(),regions,controls);
    }
}
