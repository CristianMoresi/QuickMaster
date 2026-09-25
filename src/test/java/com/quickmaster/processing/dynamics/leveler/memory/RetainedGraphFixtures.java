package com.quickmaster.processing.dynamics.leveler.memory;

import java.util.BitSet;
import com.quickmaster.processing.dynamics.leveler.model.*;

/** Tiny graph fixtures only, not official attestations and not representative audio/benchmark input. */
final class RetainedGraphFixtures
{
    private RetainedGraphFixtures() { }
    static <T> FrozenList<T> list(Object... values) { return new FrozenList<>(values); }
    static ShadowAnalysisSnapshot minimum(boolean bound)
    {
        SegmentId id=new SegmentId(0); FrameRange range=new FrameRange(0,1);
        Object[] bins=new Object[32];
        for(int i=0;i<32;i++)bins[i]=new StructuralBin(new double[12],new double[8],0,0);
        SegmentDescriptor descriptor=new SegmentDescriptor(id,range,list(bins),0,MeasuredLoudness.absent(),
                0,0,0,0,0,0,new BodyContextVector(0,0,0,0,0,0),new PcmSketch(1,2048,new double[1][2048]));
        ReferencePlan plan=new ReferencePlan(list(new ReferenceTarget(id,MeasuredLoudness.absent(),0,0,0,ReferenceReason.PROTECTED)),new double[1]);
        ConformanceState state=bound?ConformanceState.UNAVAILABLE:ConformanceState.NOT_RUN;
        ConformanceReason reason=bound?ConformanceReason.CORPUS_UNAVAILABLE:ConformanceReason.NOT_RUN;
        RequiredSetReport itu=new RequiredSetReport("ITU-R-BS.2217-1","BS.2217-1","",state,list(),reason);
        RequiredSetReport ebu=new RequiredSetReport("EBU-TECH-3341-V4","LTS-5.0","",state,list(),reason);
        String hash=bound?"a".repeat(64):"";
        StandardValidationReport standard=new StandardValidationReport("QM-OFFICIAL-LOUDNESS-FILE-V1",state,"QM-LOUDNESS-CORE-BS1770-5-V1",hash,hash,hash,hash,list(itu,ebu),0);
        ShadowAnalysisCache cache=new ShadowAnalysisCache(new AudioFormat(48000,1,1),new byte[32],
                "QM-LEVELER-SHADOW-M004-V1","QM-LEVELER-V1",
                new LoudnessTimeline(19200,144000,4800,new double[1],new BitSet(1),new double[1],new BitSet(1),MeasuredLoudness.absent()),
                new FeatureTimeline(24000,list(new StructuralFrame(0,new double[12],new double[8],0,0))),
                new SegmentLayout(LayoutStatus.READY,list(range)),list(descriptor),list(new ProtectionDecision(id,new ProtectionFlags(0))),
                new SimilarityMatrix(1,list()),new GroupingResult(list(),list()),plan);
        ShadowMemoryCounters counters=new ShadowMemoryCounters(1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0);
        ShadowDiagnostics diagnostics=new ShadowDiagnostics(ShadowAnalysisStatus.DONE,511,list(),standard,counters);
        return new ShadowAnalysisSnapshot(new ShadowAnalysisResult(ShadowAnalysisStatus.DONE,plan),cache,diagnostics);
    }
    static final class UnknownHolder
    {
        final Object[] parts;
        UnknownHolder() { parts=new Object[]{new byte[32],new byte[32]}; }
    }
}
