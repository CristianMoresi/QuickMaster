package com.quickmaster.processing.dynamics.leveler;

import java.util.*;
import java.lang.reflect.Method;
import com.dspark.core.FFTReal;
import com.quickmaster.processing.dynamics.leveler.model.*;

/** Draft literal/reference calibration. Does not certify unimplemented product V2. */
public final class ComparisonV2Test {
    static int checks,expectedMutantFailures;
    static void check(boolean ok,String cause){checks++;if(!ok)throw new AssertionError("QM_V2_ORACLE_CALIBRATION "+cause);}
    static void near(double a,double b,double tolerance,String cause){check(Math.abs(a-b)<=tolerance,cause+" got="+a+" expected="+b);}
    static void rejects(Runnable action,String cause){checks++;try{action.run();}catch(IllegalArgumentException|ArithmeticException expected){return;}throw new AssertionError("QM_V2_ORACLE_CALIBRATION accepted "+cause);}
    static void killed(Runnable assertion,String cause){try{assertion.run();}catch(AssertionError expected){expectedMutantFailures++;System.out.println("MUTANT_CAUSE "+cause+" "+expected.getMessage());return;}throw new AssertionError("QM_V2_ORACLE_CALIBRATION surviving literal mutant "+cause);}

    static final class Packed implements ComparisonV2Oracle.Source {
        final AudioFormat format;final short[] shorts,longs;final byte[] sf,lf;
        Packed(AudioFormat format){this.format=format;int s=ComparisonV2Oracle.count(format.frames(),format.sampleRateHz(),40),l=ComparisonV2Oracle.count(format.frames(),format.sampleRateHz(),8);shorts=new short[s*52];longs=new short[l*36];sf=new byte[s];lf=new byte[l];}
        public AudioFormat format(){return format;}
        public int size(boolean longer){return longer?lf.length:sf.length;}
        public int flags(boolean longer,int i){return(longer?lf[i]:sf[i])&255;}
        public int packed(boolean longer,int i,int j){return(longer?longs[i*36+j]:shorts[i*52+j]);}
        void record(boolean longer,int i,int pitch,double contrast){
            short[] values=longer?longs:shorts;int stride=longer?36:52;Arrays.fill(values,i*stride,(i+1)*stride,(short)0);
            values[i*stride+Math.floorMod(pitch*3,36)]=(short)65535;(longer?lf:sf)[i]=7;
            if(!longer)for(int j=0;j<8;j++){values[i*52+36+j]=ComparisonV2Oracle.quantizeDb(-10*Math.log10(8),false);values[i*52+44+j]=ComparisonV2Oracle.quantizeDb(contrast,true);}
        }
        Packed copy(){Packed p=new Packed(format);System.arraycopy(shorts,0,p.shorts,0,shorts.length);System.arraycopy(longs,0,p.longs,0,longs.length);System.arraycopy(sf,0,p.sf,0,sf.length);System.arraycopy(lf,0,p.lf,0,lf.length);return p;}
    }
    static Packed constant(){Packed p=new Packed(new AudioFormat(40,1,480));for(boolean l:new boolean[]{false,true})for(int i=0;i<p.size(l);i++)p.record(l,i,0,10);return p;}
    static final ComparisonV2Oracle.Range A=new ComparisonV2Oracle.Range(80,200),B=new ComparisonV2Oracle.Range(280,400);
    static void groupReject(ComparisonV2Oracle.Score s,String name){check(s.h()<.85||s.t()<.80||s.a()<.82||s.c()<.85,name);}
    static void pairPass(ComparisonV2Oracle.Score s,String name){check(s.reason()==SimilarityRejectionReason.NONE&&s.h()>=.92&&s.t()>=.90&&s.a()>=.90&&s.c()>=.92,name);}
    static void clockAndPacking(){
        for(int fs:new int[]{44100,48000,96000})for(int rate:new int[]{8,40})for(long frames:new long[]{1,fs/2L,fs/2L+1,fs+7L}) {
            List<ComparisonV2Oracle.Cell> cells=ComparisonV2Oracle.cells(frames,fs,rate);long next=0;
            check(cells.size()==ComparisonV2Oracle.count(frames,fs,rate),"count");
            for(var c:cells){check(c.start()==next&&c.end()>c.start(),"contiguous real cells");near(c.center(),c.start()+(c.end()-c.start()-1)/2,0,"center");next=c.end();}
            check(next==frames,"EOF cell");
        }
        var exact=ComparisonV2Oracle.cells(44101,44100,40);check(exact.get(0).end()==1102&&exact.get(1).end()==2205,"44.1 rational alternating cadence");check(exact.get(40).end()-exact.get(40).start()==1,"partial final cell retained");
        var p=constant();var v=ComparisonV2Oracle.view(p,false,new ComparisonV2Oracle.Range(81,199));check(v.cells().get(0).index()==81&&v.cells().get(v.cells().size()-1).index()==198,"positive intersection membership");
        var small=ComparisonV2Oracle.view(p,false,new ComparisonV2Oracle.Range(80,83));var lifted=ComparisonV2Oracle.lift(small,5);check(lifted.stream().map(c->c.index()).toList().equals(List.of(80,80,81,81,82)),"ordinal repetition no downsampling");
        rejects(()->ComparisonV2Oracle.lift(small,2),"downsampled reference");
        near(ComparisonV2Oracle.quantizeDb(-1/512d,false),0,0,"negative half quantization uses round not symmetric away");near(ComparisonV2Oracle.quantizeDb(1/512d,true),1,0,"positive half Q8.8");
        rejects(()->ComparisonV2Oracle.quantizeDb(40.01,true),"contrast outside range");rejects(()->ComparisonV2Oracle.quantizeTone(Double.NaN),"nonfinite tone");
        double[] unquantized=new double[36];double norm=0;for(int j=0;j<36;j++){unquantized[j]=(j+1)*(j+1);norm+=unquantized[j];}
        for(int j=0;j<36;j++){unquantized[j]/=norm;short raw=ComparisonV2Oracle.quantizeTone(unquantized[j]);p.shorts[j]=raw;near((raw&65535)/65535d,unquantized[j],.5/65535,"raw tonal quantization");}
        double l1=0;double[] restored=ComparisonV2Oracle.tone(p,false,0);for(int j=0;j<36;j++)l1+=Math.abs(restored[j]-unquantized[j]);check(l1<=36d/65535/(1-18d/65535),"complete normalized tonal vector error");near(Arrays.stream(restored).sum(),1,1e-15,"unsigned sum normalization");
        ComparisonV2Oracle.validate(p);checks++;
        var malformed=p.copy();malformed.sf[0]=8;rejects(()->ComparisonV2Oracle.validate(malformed),"reserved flag bit");
        var invalid=p.copy();invalid.sf[0]=0;rejects(()->ComparisonV2Oracle.validate(invalid),"invalid nonzero payload");
        var absent=p.copy();absent.sf[0]=3;Arrays.fill(absent.shorts,0,36,(short)0);ComparisonV2Oracle.validate(absent);checks++;check(!ComparisonV2Oracle.tonal(absent,false,0)&&ComparisonV2Oracle.spectral(absent,false,0),"spectral present tonal absent draft clarification");
        var mismatch=p.copy();mismatch.sf[0]=3;rejects(()->ComparisonV2Oracle.validate(mismatch),"tonal payload flag mismatch");
        for(String[] pair:new String[][]{{ComparisonV2Oracle.V1_ALGORITHM,ComparisonV2Oracle.V1_PROFILE},{ComparisonV2Oracle.V2_ALGORITHM,ComparisonV2Oracle.V2_PROFILE}})check(ComparisonV2Oracle.version(pair[0],pair[1])>0,"explicit known version");
        rejects(()->ComparisonV2Oracle.version(ComparisonV2Oracle.V1_ALGORITHM,ComparisonV2Oracle.V2_PROFILE),"mixed V1/V2");rejects(()->ComparisonV2Oracle.version("unknown",ComparisonV2Oracle.V2_PROFILE),"unknown algorithm");
        check(ComparisonV2Oracle.withinBand(20,0,120,120,100,40),"exact .5s prefix border included");check(!ComparisonV2Oracle.withinBand(21,0,120,120,100,40),"allocation+2 cannot widen physical band");check(ComparisonV2Oracle.withinBand(120,120,120,120,100,40),"unequal normalized durations preserve terminal diagonal");
        rejects(()->ComparisonV2Oracle.withinBand(-1,0,120,120,100,40),"negative prefix");
    }
    static void physical(){
        double[] shape=new double[8],contrast=new double[8];Arrays.fill(shape,-10*Math.log10(8));Arrays.fill(contrast,8);
        for(double scale:new double[]{0,1,2}) {
            double[] other=contrast.clone();for(int j=0;j<8;j++)other[j]+=Math.sqrt(2)*scale*ComparisonV2Oracle.B;
            var result=ComparisonV2Oracle.physical(shape,contrast,shape,other);near(result.squaredDb(),Math.pow(scale*ComparisonV2Oracle.B,2),1e-12,"known physical squared deviation");near(1-result.distance(),Math.exp(-scale*scale/2),1e-12,"B/2B physical mapping");
        }
        double[] twice=contrast.clone();for(int j=0;j<8;j++)twice[j]*=2;
        var radius=ComparisonV2Oracle.physical(shape,contrast,shape,twice);check(radius.distance()>.8,"equal direction different contrast radius discriminates");
        killed(()->near(0,radius.distance(),1e-12,"direction-only cosine/drop radius"),"CONTRAST_RADIUS");
        var p=constant();for(int i=280;i<400;i++)p.record(false,i,0,20);
        var s=ComparisonV2Oracle.score(p,A,B);check(s.t()<.90,"literal physical T pair floor");
        killed(()->check(1<.90,"unit/no-op timbre"),"UNIT_TIMBRE");
    }
    static void orderAndRotation(){
        var p=constant();int[] notes={0,2,7,11},reordered={0,7,2,11},cyclic={2,7,11,0};
        for(int k=0;k<120;k++){p.record(false,80+k,notes[k/5%4],10);p.record(false,280+k,notes[k/5%4],10);}
        var same=ComparisonV2Oracle.score(p,A,B);pairPass(same,"literal true ordered repeat");near(same.c(),1,1e-12,"exact repeat score");check(same.path().size()==120&&same.path().get(119).i()==120&&same.path().get(119).j()==120,"full path endpoint");check(same.path().stream().allMatch(s->s.operation()==1),"tie preference diagonal");
        var changed=p.copy();for(int k=0;k<120;k++)changed.record(false,280+k,reordered[k/5%4],10);
        var wrong=ComparisonV2Oracle.score(changed,A,B);groupReject(wrong,"literal same-note-multiset reorder");
        var cyc=p.copy();for(int k=0;k<120;k++)cyc.record(false,280+k,cyclic[k/5%4],10);var phase=ComparisonV2Oracle.score(cyc,A,B);check(phase.a()<1,"cyclic alignment pays global ends");pairPass(phase,"bounded cyclic phase positive");
        killed(()->groupReject(same,"pooled means makes permutation a repeat"),"POOLED_ORDER");
        var late=p.copy();for(int k=64;k<120;k++)late.record(false,280+k,reordered[k/5%4],10);var tail=ComparisonV2Oracle.score(late,A,B);check(tail.a()<same.a()-.1,"later reordered passage beyond first32/64 observed");
        var tiny=p.copy();tiny.record(false,399,5,10);var suffix=ComparisonV2Oracle.score(tiny,A,B);check(suffix.a()<same.a(),"one changed final observation influences score without artificial fixed floor");check(suffix.cellsB().get(suffix.cellsB().size()-1).index()==399,"last real observation visited");
        killed(()->check(same.a()<same.a()-.1,"prefix-only score"),"PREFIX_TRUNCATION");
        var transposed=constant();for(int i=280;i<400;i++)transposed.record(false,i,2,10);for(int i=56;i<80;i++)transposed.record(true,i,2,10);
        var trans=ComparisonV2Oracle.score(transposed,A,B);check(trans.rotation()==2&&trans.support()==trans.longValid(),"one global rotation/support");near(trans.h(),.95,1e-12,"one .05 penalty");
        check(!ComparisonV2Oracle.stable(trans,same)&&!ComparisonV2Oracle.stable(same,trans),"both zero/nonzero endpoint direction changes reject");
        var mixed=constant();for(int i=56;i<80;i++)mixed.record(true,i,(i-56)%10<7?2:3,10);
        var ambiguous=ComparisonV2Oracle.score(mixed,A,B);check(ambiguous.reason()==SimilarityRejectionReason.AMBIGUOUS,"nonzero needs80percent support");
        killed(()->check(SimilarityRejectionReason.NONE==ambiguous.reason(),"per-window rotation incorrectly claims valid"),"PER_WINDOW_ROTATION");
        var conflict=constant();for(int i=280;i<400;i++)conflict.record(false,i,7,10);var differentLead=ComparisonV2Oracle.score(conflict,A,B);groupReject(differentLead,"shared sustained accompaniment cannot erase short conflict");
        killed(()->check(0>=.8,"required positive actual action cannot be no-op"),"NOOP_POSITIVE_LITERAL");
        System.out.println("LITERAL_SCORES repeat="+same.c()+" reorder="+wrong.c()+" phase="+phase.c()+" late="+tail.c()+" transpose="+trans.h());
    }
    static void endpointsAndCoverage(){
        var p=constant();var comparison=ComparisonV2Oracle.compare(p,A,B);check(comparison.variants().size()==8,"all9 observations");
        long[][] expected={{60,200,280,400},{100,200,280,400},{80,180,280,400},{80,220,280,400},{80,200,260,400},{80,200,300,400},{80,200,280,380},{80,200,280,420}};
        for(int slot=0;slot<8;slot++) {
            var v=comparison.variants().get(slot);long[] e=expected[slot];check(v.a().start()==e[0]&&v.a().end()==e[1]&&v.b().start()==e[2]&&v.b().end()==e[3],"endpoint slot order "+slot);
            check(v.score().cellsA().get(0).index()==e[0]&&v.score().cellsA().get(v.score().cellsA().size()-1).index()==e[1]-1,"fresh first view "+slot);
            check(v.score().cellsB().get(0).index()==e[2]&&v.score().cellsB().get(v.score().cellsB().size()-1).index()==e[3]-1,"fresh second view "+slot);
        }
        var extra=p.copy();for(int i=60;i<80;i++)extra.record(false,i,7,10);var observed=ComparisonV2Oracle.compare(extra,A,B);
        near(observed.central().a(),1,1e-12,"outside central feature has no central effect");check(observed.variants().get(0).score().a()<1-.05,"added cells affect own endpoint DP");check(observed.reason()==SimilarityRejectionReason.UNSTABLE_BOUNDARY,"endpoint deletion falsifier");
        killed(()->check(comparison.reason()==SimilarityRejectionReason.UNSTABLE_BOUNDARY,"central-only/endpoint deletion"),"ENDPOINT_DELETION");
        var invalid=p.copy();for(int i=80;i<100;i++){invalid.sf[i]=0;Arrays.fill(invalid.shorts,i*52,(i+1)*52,(short)0);}
        var missing=ComparisonV2Oracle.score(invalid,A,B);check(missing.reason()==SimilarityRejectionReason.INVALID_BIN_RUN,"five invalid bins reject rather than delete");
        var clipped=ComparisonV2Oracle.compare(p,new ComparisonV2Oracle.Range(0,120),new ComparisonV2Oracle.Range(360,480));check(clipped.variants().size()==8,"clipped EOF all variants");check(clipped.variants().get(0).a().start()==0&&clipped.variants().get(7).b().end()==480,"EOF clamp");
        var collapsed=ComparisonV2Oracle.compare(p,new ComparisonV2Oracle.Range(0,10),new ComparisonV2Oracle.Range(100,110));check(collapsed.variants().size()==8,"collapsed range does not stop observations");check(collapsed.variants().get(1).score().reason()==SimilarityRejectionReason.UNSTABLE_BOUNDARY,"collapsed range rejected");
    }
    static double[] vendorPowers(float[] pcm,AudioFormat format,long center,int support,int size,int[] bins) {
        FFTReal fft=new FFTReal(size);float[] time=new float[size],frequency=new float[fft.getFrequencyDomainSize()],magnitude=new float[fft.getNumBins()];double[] p=new double[bins.length];double h2=0;
        for(int n=0;n<support;n++){double h=.5-.5*Math.cos(2*Math.PI*n/(support-1d));h2+=h*h;}
        for(int c=0;c<format.channels();c++) {
            Arrays.fill(time,0);for(int n=0;n<support;n++){long at=center-(support-1L)/2+n;double x=at<0||at>=format.frames()?0:pcm[Math.toIntExact(at*format.channels()+c)];time[n]=(float)(x*(.5-.5*Math.cos(2*Math.PI*n/(support-1d))));}
            fft.forward(time,frequency);fft.computeMagnitudes(frequency,magnitude);for(int j=0;j<bins.length;j++)p[j]+=(double)magnitude[bins[j]]*magnitude[bins[j]]/(format.channels()*h2);
        }
        return p;
    }
    static void dft(){
        for(int fs:new int[]{44100,48000,96000})for(int channels:new int[]{1,2})for(double hz:new double[]{55,220,523.251}) {
            int frames=fs/2,support=(int)Math.round(.25*fs),size=1;while(size<support)size*=2;
            AudioFormat format=new AudioFormat(fs,channels,frames);float[] pcm=new float[frames*channels],scaled=new float[pcm.length];
            for(int n=0;n<frames;n++)for(int c=0;c<channels;c++){pcm[n*channels+c]=(float)(.3*(c==0?1:-1)*Math.sin(2*Math.PI*hz*n/fs+.37));scaled[n*channels+c]=.1f*pcm[n*channels+c];}
            int k=(int)Math.round(hz*size/fs);int[] bins={k-1,k,k+1};long center=frames/2;
            double[] direct=ComparisonV2Oracle.dftPowers(pcm,format,center,support,size,bins),actual=vendorPowers(pcm,format,center,support,size,bins),gain=ComparisonV2Oracle.dftPowers(scaled,format,center,support,size,bins);
            for(int j=0;j<3;j++){near(actual[j]/direct[j],1,2e-5,"vendor vs independent source-support DFT "+fs+"/"+channels+"/"+hz);near(gain[j]/direct[j],.01,2e-9,"gain relative DFT");}
            double resolved=ComparisonV2Oracle.interpolate(direct[0],direct[1],direct[2],k,fs,size);double cents=1200*Math.log(resolved/hz)/Math.log(2);check(Math.abs(cents)<=1,"independent long-support interpolated frequency "+cents);
            System.out.println("DFT fs="+fs+" channels="+channels+" hz="+hz+" resolved="+resolved+" cents="+cents+" power="+direct[1]);
            if(channels==2){float[] fold=new float[frames];for(int n=0;n<frames;n++)fold[n]=(pcm[2*n]+pcm[2*n+1])*.5f;double[] folded=ComparisonV2Oracle.dftPowers(fold,new AudioFormat(fs,1,frames),center,support,size,bins);check(direct[1]>0&&folded[1]==0,"joint power survives exact antiphase");}
        }
    }
    static ComparisonV2Oracle.Source source(ComparisonTimeline timeline){
        return new ComparisonV2Oracle.Source(){
            public AudioFormat format(){return timeline.format();}
            public ComparisonTimeline retainedTimeline(){return timeline;}
            public int size(boolean longer){return longer?timeline.longCount():timeline.shortCount();}
            public int flags(boolean longer,int row){return(longer?timeline.longFlagsAt(row):timeline.shortFlagsAt(row))&255;}
            public int packed(boolean longer,int row,int component){return longer?timeline.longValueAt(row,component):timeline.shortValueAt(row,component);}
        };
    }
    static ComparisonTimeline timeline(Packed packed){return new ComparisonTimeline(packed.format,packed.shorts,packed.longs,packed.sf,packed.lf);}
    static FrameRange range(ComparisonV2Oracle.Range r){return new FrameRange(r.start(),r.end());}
    static void sameScore(SimilarityScore actual,ComparisonV2Oracle.Score expected,String label){
        check(actual!=null,label+" actual score exists");check(actual.rejectionReason()==expected.reason(),label+" reason "+actual.rejectionReason()+"/"+expected.reason());
        check(actual.validBins()==expected.count(),label+" coverage");check(actual.chromaRotation()==expected.rotation(),label+" global rotation");
        near(actual.h(),expected.h(),1e-11,label+" H");near(actual.t(),expected.t(),1e-11,label+" T");near(actual.a(),expected.a(),1e-11,label+" A");near(actual.c(),expected.c(),1e-11,label+" C");
        if(actual.isAccepted()){
            ComparisonV2Oracle.requireHarmonicIdentity(actual.h(),expected,label);
            double sh=expected.path().stream().mapToDouble(p->p.h()).sum(),st=expected.path().stream().mapToDouble(p->p.t()).sum(),sa=expected.path().stream().mapToDouble(p->p.cost()).sum();double rho=actual.validBins()/32d;
            if(actual.h()>0)near((1-actual.h()/rho-(actual.chromaRotation()==0?0:.05))*expected.n(),sh,1e-8,label+" tie-selected harmonic sum");
            near((1-actual.t()/rho)*expected.n(),st,1e-8,label+" tie-selected timbral sum");near((1-actual.a()/rho)*expected.n(),sa,1e-8,label+" full path edit sum");
        }
    }
    static void productCase(String label,Packed packed,ComparisonV2Oracle.Range a,ComparisonV2Oracle.Range b)throws Exception {
        ComparisonTimeline timeline=timeline(packed);ComparisonV2Oracle.Source observed=source(timeline);ComparisonV2Oracle.validate(observed);
        var oracle=ComparisonV2Oracle.compare(observed,a,b);var comparator=new ComparisonComparator();
        var actual=comparator.compare(range(a),range(b),timeline.format(),timeline,LevelerCalibrationProfile.V2,new CancellationToken());
        check(actual!=null&&actual.rejectionReason()==oracle.reason(),label+" all-endpoint result "+(actual==null?"null":actual.rejectionReason())+"/"+oracle.reason());
        if(actual.isAccepted())sameScore(actual,oracle.central(),label+" returned central");
        Method kernel=ComparisonComparator.class.getDeclaredMethod("kernel",FrameRange.class,FrameRange.class,AudioFormat.class,ComparisonTimeline.class,CancellationToken.class);kernel.setAccessible(true);
        sameScore((SimilarityScore)kernel.invoke(null,range(a),range(b),timeline.format(),timeline,new CancellationToken()),oracle.central(),label+" central kernel");
        for(var variant:oracle.variants())if(variant.a().length()>0&&variant.b().length()>0)
            sameScore((SimilarityScore)kernel.invoke(null,range(variant.a()),range(variant.b()),timeline.format(),timeline,new CancellationToken()),variant.score(),label+" slot"+variant.slot());
        System.out.println("PRODUCT_CASE "+label+" H="+oracle.central().h()+" T="+oracle.central().t()+" A="+oracle.central().a()+" reason="+oracle.reason());
    }
    static void product()throws Exception {
        var doubling=ComparisonComparator.class.getDeclaredField("POWER_DOUBLING_DB");doubling.setAccessible(true);near(doubling.getDouble(null),10*StrictMath.log10(2),0,"compile-time physical constant preserves exact double");
        productCase("stationary",constant(),A,B);
        var ordered=constant();int[] notes={0,2,7,11},reorder={0,7,2,11};for(int k=0;k<120;k++){ordered.record(false,80+k,notes[k/5%4],10);ordered.record(false,280+k,notes[k/5%4],10);}
        productCase("true_ordered_repeat",ordered,A,B);
        var changed=ordered.copy();for(int k=0;k<120;k++)changed.record(false,280+k,reorder[k/5%4],10);productCase("same_histogram_reorder",changed,A,B);
        var late=ordered.copy();late.record(false,399,5,10);productCase("final_cell_change",late,A,B);
        var transposed=constant();for(int i=280;i<400;i++)transposed.record(false,i,2,10);for(int i=56;i<80;i++)transposed.record(true,i,2,10);productCase("global_rotation2",transposed,A,B);
        var radius=constant();for(int i=280;i<400;i++)radius.record(false,i,0,20);productCase("contrast_radius",radius,A,B);
        var edge=constant();for(int i=60;i<80;i++)edge.record(false,i,7,10);productCase("added_endpoint_cells",edge,A,B);
        productCase("unequal_duration",constant(),A,new ComparisonV2Oracle.Range(280,380));
        productCase("clipped_EOF",constant(),new ComparisonV2Oracle.Range(0,120),new ComparisonV2Oracle.Range(360,480));
        var timeline=timeline(constant());var comparator=new ComparisonComparator();var token=new CancellationToken();token.cancel();check(comparator.compare(range(A),range(B),timeline.format(),timeline,LevelerCalibrationProfile.V2,token)==null,"cancelled comparison cannot publish partial score");
        check(comparator.compare(range(A),range(B),new AudioFormat(40,1,480),timeline,LevelerCalibrationProfile.V2,new CancellationToken()).rejectionReason()==SimilarityRejectionReason.NON_FINITE,"same values different source alias rejected");
        check(comparator.compare(range(A),range(B),timeline.format(),timeline,LevelerCalibrationProfile.V1,new CancellationToken()).rejectionReason()==SimilarityRejectionReason.NON_FINITE,"V1 profile cannot dispatch V2");
    }
    @org.junit.jupiter.api.Test void independentReferenceAndRealComparator()throws Exception {checks=0;expectedMutantFailures=0;clockAndPacking();physical();orderAndRotation();endpointsAndCoverage();product();}
    @org.junit.jupiter.api.Test void independentPhysicalFrequencyAndPower(){checks=0;dft();}
    @org.junit.jupiter.api.Test void exactCacheDispatchRejectsMissingForeignAndMixedEvidence()throws Exception {
        var format=new AudioFormat(48000,1,4801);
        var snapshot=new LevelerAnalysisEngine().analyzeShadow(new float[4801],format,new CancellationToken());
        check(snapshot!=null,"real current cache exists for dispatcher controls");var cache=snapshot.cache();
        check(ComparisonV2Oracle.version(cache)==2,"real retained V2 dispatch");
        var legacy=new ShadowAnalysisCache(format,cache.copyPcmFingerprintSha256(),ComparisonV2Oracle.V1_ALGORITHM,ComparisonV2Oracle.V1_PROFILE,
                cache.loudness(),cache.features(),cache.layout(),cache.descriptors(),cache.protections(),cache.similarity(),cache.grouping(),cache.referencePlan());
        check(ComparisonV2Oracle.version(legacy)==1,"explicit historical V1 dispatch");
        var r=cache.descriptors().get(0).range();
        var historical=MusicalContextOracle.compare(legacy.features(),format,r,r);
        check(ComparisonV2Oracle.observe(legacy,r,r).central().equals(historical.central()),"V1 observer remains historical formula");
        for(String fieldName:List.of("algorithmId","profileId","comparison")) {
            var field=cache.getClass().getDeclaredField(fieldName);field.setAccessible(true);Object old=field.get(cache);
            List<Object> bad=fieldName.equals("algorithmId")?List.of("unknown",ComparisonV2Oracle.V1_ALGORITHM)
                    :fieldName.equals("profileId")?List.of("unknown",ComparisonV2Oracle.V1_PROFILE)
                    :Arrays.asList(null,new ComparisonTimeline(new AudioFormat(48000,1,4801),new short[5*52],new short[36],new byte[5],new byte[1]));
            try{for(Object value:bad){field.set(cache,value);rejects(()->ComparisonV2Oracle.version(cache),"invalid retained identity "+fieldName);}}
            finally{field.set(cache,old);}
        }
        var field=legacy.getClass().getDeclaredField("comparison");field.setAccessible(true);
        try{field.set(legacy,cache.comparison());rejects(()->ComparisonV2Oracle.version(legacy),"V1 cannot carry comparison evidence");}
        finally{field.set(legacy,null);}
        rejects(()->MusicalComparisonV2Test.observe(cache,r,r,null),"V2 cannot omit explicit source adapter");
        var alien=timeline(constant());rejects(()->MusicalComparisonV2Test.observe(cache,r,r,source(alien)),"foreign adapter");
        check(ComparisonV2Oracle.version(cache)==2,"mutation controls restore current cache");
    }
    public static void main(String[] args)throws Exception {if(args.length!=1)throw new IllegalArgumentException("literal|dft|product");if(args[0].equals("literal")){clockAndPacking();physical();orderAndRotation();endpointsAndCoverage();}else if(args[0].equals("dft"))dft();else if(args[0].equals("product"))product();else throw new IllegalArgumentException();System.out.println("COUNTS checks="+checks+" failed=0 expectedMutantFailures="+expectedMutantFailures+" productV2Executed="+args[0].equals("product"));}
}
