package com.quickmaster.processing.dynamics.leveler;

import java.math.BigInteger;
import java.util.*;
import com.quickmaster.processing.dynamics.leveler.model.*;

/** Independent draft V2 reference: enumerated membership and full grid/backtrace, never rolling rows. */
public final class ComparisonV2Oracle {
    static final String V1_ALGORITHM="QM-LEVELER-SHADOW-M004-V1", V1_PROFILE="QM-LEVELER-V1";
    static final String V2_ALGORITHM="QM-LEVELER-S001-COMPARISON-V2", V2_PROFILE="QM-LEVELER-V2";
    static final double B=10*Math.log10(2), EPS=1e-12;
    // Test-only: the unchanged 60s context fixture has 60.5s endpoint views at 40 Hz.
    // One double-cost/byte-predecessor grid payload is 9*(2421)^2 = 52,751,169 bytes.
    static final int MAX_REFERENCE_N=2420;
    interface Source {
        AudioFormat format();
        default ComparisonTimeline retainedTimeline(){return null;}
        int size(boolean longer);
        int flags(boolean longer,int row);
        int packed(boolean longer,int row,int column);
    }
    record Cell(int index,long start,long end,long center) { }
    record Range(long start,long end) { long length(){return end-start;} }
    record View(Range range,List<Cell> cells) { }
    record Physical(double squaredDb,double distance,double[] weights) { }
    record Local(double h,double t,boolean valid,double squaredDb) { }
    record Observation(boolean spectral,boolean shortTonal,boolean longTonal,double[] shortTone,double[] longTone,double[] shape,double[] contrast) { }
    record Step(int i,int j,byte operation,double h,double t,double cost) { }
    record Score(double h,double t,double a,double c,int rotation,int count,
                 SimilarityRejectionReason reason,double[] rotationMeans,int longValid,int support,
                 int n,int width,int invalidRun,List<Step> path,List<Cell> cellsA,List<Cell> cellsB) { }
    record Variant(int slot,Range a,Range b,Score score) { }
    record Comparison(Score central,List<Variant> variants,SimilarityRejectionReason reason) { }
    record Observed(int version,MusicalContextOracle.Score central,List<?> variants,
                    SimilarityRejectionReason reason,Object evidence) { }

    /** Per-test memo only: exact complete source cells/format and ranges, never product scores. */
    static final class ReferenceMemo {
        private final Map<String,Comparison> expected=new HashMap<>();
        Comparison compare(Source source,Range a,Range b) {
            String key=sourceIdentity(source)+":"+a.start+":"+a.end+":"+b.start+":"+b.end;
            return expected.computeIfAbsent(key,ignored->ComparisonV2Oracle.compare(source,a,b));
        }
        Observed observe(ShadowAnalysisCache cache,FrameRange a,FrameRange b) {
            if(version(cache)==1)return ComparisonV2Oracle.observe(cache,a,b);
            Comparison value=compare(ComparisonV2Test.source(cache.comparison()),range(a),range(b));
            Score s=value.central;
            return new Observed(2,new MusicalContextOracle.Score(s.h,s.t,s.a,s.c,s.rotation,s.count,s.reason),value.variants,value.reason,value);
        }
    }
    static String sourceIdentity(Source source) {
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            var header=java.nio.ByteBuffer.allocate(16).putInt(source.format().sampleRateHz())
                    .putInt(source.format().channels()).putLong(source.format().frames());
            digest.update(header.array());
            for(boolean longer:new boolean[]{false,true}) {
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(source.size(longer)).array());
                for(int row=0;row<source.size(longer);row++) {
                    digest.update((byte)source.flags(longer,row));
                    for(int column=0;column<(longer?36:52);column++) {
                        int value=source.packed(longer,row,column);digest.update((byte)(value>>>8));digest.update((byte)value);
                    }
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    static void requireVersion(ShadowAnalysisCache cache,Observed observed) {
        if(observed==null||version(cache)!=observed.version)
            throw new IllegalArgumentException("QM_CONTEXT_ORACLE_VERSION_MISMATCH");
    }

    /** Exact cache dispatch: an adapter cannot manufacture an absent or foreign retained timeline. */
    static int version(ShadowAnalysisCache cache) {
        if(cache==null)throw new IllegalArgumentException("Missing comparison cache");
        int result=version(cache.algorithmId(),cache.profileId());
        if(result==1&&cache.comparison()!=null)throw new IllegalArgumentException("V1 has V2 evidence");
        if(result==2&&(cache.comparison()==null||cache.comparison().format()!=cache.format()))
            throw new IllegalArgumentException("V2 requires retained comparison and exact format identity");
        return result;
    }
    static Observed observe(ShadowAnalysisCache cache,FrameRange a,FrameRange b) {
        int version=version(cache);
        if(version==1) {
            var old=MusicalContextOracle.compare(cache.features(),cache.format(),a,b);
            return new Observed(1,old.central(),old.variants(),old.reason(),old);
        }
        var current=compare(ComparisonV2Test.source(cache.comparison()),range(a),range(b));
        var s=current.central();
        return new Observed(2,new MusicalContextOracle.Score(s.h,s.t,s.a,s.c,s.rotation,s.count,s.reason),current.variants,current.reason,current);
    }
    static Range range(FrameRange r){return new Range(r.startInclusive(),r.endExclusive());}
    static FrameRange range(Range r){return new FrameRange(r.start,r.end);}
    static SimilarityScore product(ShadowAnalysisCache cache,int first,int second) {
        var a=cache.descriptors().get(first);var b=cache.descriptors().get(second);
        return version(cache)==1?new SegmentComparator().compare(a,b,cache.format(),cache.features(),LevelerCalibrationProfile.V1)
                :new ComparisonComparator().compare(a.range(),b.range(),cache.format(),cache.comparison(),LevelerCalibrationProfile.V2,new CancellationToken());
    }
    /** Re-sum every full-grid backtrace step; gaps contribute exactly .5, never disappear. */
    static double harmonicFromPath(Score expected) {
        if(expected.reason!=SimilarityRejectionReason.NONE||expected.n<=0||expected.path.isEmpty())
            throw new IllegalArgumentException("No evaluable complete harmonic path");
        int i=0,j=0;double sum=0;
        for(Step step:expected.path) {
            if(step.operation==1){i++;j++;}
            else if(step.operation==2){i++;if(step.h!=.5)throw new IllegalArgumentException("Non-normative harmonic gap");}
            else if(step.operation==3){j++;if(step.h!=.5)throw new IllegalArgumentException("Non-normative harmonic gap");}
            else throw new IllegalArgumentException("Unknown path operation");
            if(step.i!=i||step.j!=j||!Double.isFinite(step.h)||step.h<0)throw new IllegalArgumentException("Incomplete harmonic backtrace");
            sum+=step.h;
        }
        if(i!=expected.n||j!=expected.n)throw new IllegalArgumentException("Missing full path endpoint");
        return expected.count/32d*clamp(1-sum/expected.n-(expected.rotation==0?0:.05));
    }
    static void requireHarmonicIdentity(double actual,Score expected,String label) {
        double reconstructed=harmonicFromPath(expected);
        if(!Double.isFinite(actual)||Math.abs(actual-reconstructed)>1e-12)
            throw new AssertionError("QM_V2_HARMONIC_PENALTY_IDENTITY "+label+" actual="+actual+" expected="+reconstructed+" rotation="+expected.rotation+" rho="+expected.count/32d+" N="+expected.n);
    }
    static void scoreDifferences(SimilarityScore actual,Score expected,String label,List<String> failures) {
        if(actual==null)throw new IllegalStateException("Missing actual comparator score: "+label);
        if(actual.rejectionReason()!=expected.reason)failures.add(label+" independent reason "+actual.rejectionReason()+"/"+expected.reason);
        if(actual.chromaRotation()!=expected.rotation||actual.validBins()!=expected.count)failures.add(label+" independent rotation/coverage");
        double[] got={actual.h(),actual.t(),actual.a(),actual.c()},want={expected.h,expected.t,expected.a,expected.c};
        for(int k=0;k<4;k++)if(!Double.isFinite(got[k])||Math.abs(got[k]-want[k])>1e-12)failures.add(label+" independent "+"HTAC".charAt(k)+" actual="+got[k]+" expected="+want[k]);
        if(expected.reason==SimilarityRejectionReason.NONE)try{requireHarmonicIdentity(actual.h(),expected,label);}catch(AssertionError mismatch){failures.add(mismatch.getMessage());}
    }
    /** Full-grid expectation and actual central/eight endpoint kernels are independently compared. */
    static void verifyProduct(ShadowAnalysisCache cache,FrameRange a,FrameRange b,SimilarityScore actual,
                              Observed observed,String label,List<String> failures)throws ReflectiveOperationException {
        if(version(cache)!=observed.version)throw new IllegalArgumentException("Observer/cache version mismatch");
        if(actual==null)throw new IllegalStateException("Missing actual public comparator score");
        if(actual.rejectionReason()!=observed.reason)failures.add(label+" independent comparator reason "+actual.rejectionReason()+"/"+observed.reason);
        if(observed.version==1) {
            if(actual.isAccepted()) {
                var s=observed.central;
                double[] got={actual.h(),actual.t(),actual.a(),actual.c()},want={s.h(),s.t(),s.a(),s.c()};
                for(int k=0;k<4;k++)if(!Double.isFinite(got[k])||Math.abs(got[k]-want[k])>1e-12)failures.add(label+" historical independent "+"HTAC".charAt(k));
                if(actual.chromaRotation()!=s.rotation())failures.add(label+" historical independent rotation");
            }
            return;
        }
        var independent=(Comparison)observed.evidence;
        if(independent.variants.size()!=8)throw new IllegalStateException("Incomplete endpoint oracle");
        if(actual.isAccepted())scoreDifferences(actual,independent.central,label+" public central",failures);
        var kernel=ComparisonComparator.class.getDeclaredMethod("kernel",FrameRange.class,FrameRange.class,AudioFormat.class,ComparisonTimeline.class,CancellationToken.class);kernel.setAccessible(true);
        scoreDifferences((SimilarityScore)kernel.invoke(null,a,b,cache.format(),cache.comparison(),new CancellationToken()),independent.central,label+" central",failures);
        for(Variant v:independent.variants)if(v.a.length()>0&&v.b.length()>0)
            scoreDifferences((SimilarityScore)kernel.invoke(null,range(v.a),range(v.b),cache.format(),cache.comparison(),new CancellationToken()),v.score,label+" endpoint"+v.slot,failures);
    }

    static int version(String algorithm,String profile) {
        if(V1_ALGORITHM.equals(algorithm)&&V1_PROFILE.equals(profile))return 1;
        if(V2_ALGORITHM.equals(algorithm)&&V2_PROFILE.equals(profile))return 2;
        throw new IllegalArgumentException("Unknown or mixed comparison identity: "+algorithm+" / "+profile);
    }
    static long boundary(long i,int fs,int rate) {
        if(i<0||fs<=0||rate<=0)throw new IllegalArgumentException();
        return BigInteger.valueOf(i).multiply(BigInteger.valueOf(fs)).divide(BigInteger.valueOf(rate)).longValueExact();
    }
    static int count(long frames,int fs,int rate) {
        if(frames<0||fs<=0||rate<=0)throw new IllegalArgumentException();
        return BigInteger.valueOf(frames).multiply(BigInteger.valueOf(rate)).add(BigInteger.valueOf(fs-1L))
                .divide(BigInteger.valueOf(fs)).intValueExact();
    }
    static List<Cell> cells(long frames,int fs,int rate) {
        List<Cell> answer=new ArrayList<>();
        for(int i=0;i<count(frames,fs,rate);i++) {
            long left=boundary(i,fs,rate),right=Math.min(frames,boundary(i+1L,fs,rate));
            if(right<=left)throw new IllegalArgumentException("empty cell");
            answer.add(new Cell(i,left,right,left+(right-left-1)/2));
        }
        return List.copyOf(answer);
    }
    static View view(Source s,boolean longer,Range r) {
        if(r.start<0||r.end>s.format().frames()||r.length()<=0)throw new IllegalArgumentException("range");
        List<Cell> selected=new ArrayList<>();
        for(Cell c:cells(s.format().frames(),s.format().sampleRateHz(),longer?8:40))
            if(Math.min(c.end,r.end)>Math.max(c.start,r.start))selected.add(c);
        if(selected.isEmpty())throw new IllegalArgumentException("empty view");
        return new View(r,List.copyOf(selected));
    }
    static List<Cell> lift(View v,int n) {
        if(n<v.cells.size())throw new IllegalArgumentException("reference must not downsample");
        List<Cell> answer=new ArrayList<>();
        for(int k=0;k<n;k++)answer.add(v.cells.get(BigInteger.valueOf(k).multiply(BigInteger.valueOf(v.cells.size()))
                .divide(BigInteger.valueOf(n)).intValueExact()));
        return List.copyOf(answer);
    }
    static int nearestLong(Source s,long center) {
        long distance=Long.MAX_VALUE;int best=-1;
        for(Cell c:cells(s.format().frames(),s.format().sampleRateHz(),8)) {
            long d=Math.abs(c.center-center);if(d<distance){distance=d;best=c.index;}
        }
        return best;
    }
    static boolean spectral(Source s,boolean longer,int row){return (s.flags(longer,row)&3)==3;}
    static boolean tonal(Source s,boolean longer,int row){return (s.flags(longer,row)&7)==7;}
    static double[] tone(Source s,boolean longer,int row) {
        double[] v=new double[36];long sum=0;
        for(int j=0;j<36;j++){v[j]=s.packed(longer,row,j)&65535;sum+=(long)v[j];}
        if(sum>0)for(int j=0;j<36;j++)v[j]/=sum;return v;
    }
    static double[] db(Source s,int row,int offset) {
        double[] v=new double[8];for(int j=0;j<8;j++)v[j]=(short)s.packed(false,row,j+offset)/256d;return v;
    }
    static short quantizeTone(double value) {
        if(!Double.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("tonal value");
        return (short)Math.round(value*65535);
    }
    static short quantizeDb(double value,boolean contrast) {
        if(!Double.isFinite(value)||(contrast?(value<0||value>40):(value< -40||value>0)))throw new IllegalArgumentException("dB value");
        return (short)Math.round(value*256);
    }
    static void validate(Source s) {
        if(s==null||s.format()==null)throw new IllegalArgumentException("null source");
        for(boolean longer:new boolean[]{false,true}) {
            if(s.size(longer)!=count(s.format().frames(),s.format().sampleRateHz(),longer?8:40))throw new IllegalArgumentException("length");
            for(int i=0;i<s.size(longer);i++) {
                int flags=s.flags(longer,i);if(flags<0||(flags&~7)!=0)throw new IllegalArgumentException("flags");
                if((flags&4)!=0&&(flags&2)==0)throw new IllegalArgumentException("tonal without spectrum");
                long total=0;for(int j=0;j<36;j++)total+=s.packed(longer,i,j)&65535;
                boolean valid=spectral(s,longer,i);
                if(!valid&&(flags&4)!=0)throw new IllegalArgumentException("invalid populated branch");
                if(valid&&(((flags&4)!=0)!=(total>0)))throw new IllegalArgumentException("tonal flag mismatch");
                for(int j=0;j<(longer?36:52);j++) {
                    int raw=s.packed(longer,i,j);
                    if(!valid&&raw!=0)throw new IllegalArgumentException("invalid nonzero payload");
                    if(!longer&&j>=36){int signed=(short)raw;if(j<44?(signed< -10240||signed>0):(signed<0||signed>10240))throw new IllegalArgumentException("dB range");}
                }
            }
        }
    }
    static double tv(double[] a,double[] b,int rotation) {
        double sum=0;for(int j=0;j<36;j++)sum+=Math.abs(a[j]-b[(j+3*rotation)%36]);return .5*sum;
    }
    static Physical physical(double[] sa,double[] ca,double[] sb,double[] cb) {
        double[] w=new double[8];double total=0,d2=0;
        for(int j=0;j<8;j++){w[j]=Math.max(Math.pow(10,sa[j]/10),Math.pow(10,sb[j]/10));total+=w[j];}
        for(int j=0;j<8;j++){w[j]/=total;d2+=.5*w[j]*(Math.pow(sa[j]-sb[j],2)+Math.pow(ca[j]-cb[j],2));}
        return new Physical(d2,1-Math.exp(-d2/(2*B*B)),w);
    }
    static Local local(Source s,Cell a,Cell b,int rotation) {
        return local(observe(s,a),observe(s,b),rotation);
    }
    static Observation observe(Source s,Cell a) {
        int l=nearestLong(s,a.center);
        return new Observation(spectral(s,false,a.index),tonal(s,false,a.index),tonal(s,true,l),tone(s,false,a.index),tone(s,true,l),db(s,a.index,36),db(s,a.index,44));
    }
    static Local local(Observation a,Observation b,int rotation) {
        boolean as=a.shortTonal,bs=b.shortTonal,al=a.longTonal,bl=b.longTonal;
        boolean valid=a.spectral&&b.spectral&&((as&&bs)||(al&&bl));
        if(!valid)return new Local(1,1,false,Double.NaN);
        double sh=as&&bs?tv(a.shortTone,b.shortTone,rotation):(as||bs?1:0);
        double lh=al&&bl?tv(a.longTone,b.longTone,rotation):(al||bl?1:0);
        Physical physical=physical(a.shape,a.contrast,b.shape,b.contrast);
        return new Local(Math.max(sh,lh),physical.distance,true,physical.squaredDb);
    }
    static Score score(Source s,Range a,Range b) {
        validate(s);
        double ratio=a.length()/(double)b.length();
        if(a.length()<=0||b.length()<=0||a.start<0||b.start<0||a.end>s.format().frames()||b.end>s.format().frames())return rejected(SimilarityRejectionReason.NON_FINITE);
        if(ratio<.75||ratio>1.33)return rejected(SimilarityRejectionReason.DURATION_RATIO);
        View av=view(s,true,a),bv=view(s,true,b);int nl=Math.max(av.cells.size(),bv.cells.size());
        List<Cell> al=lift(av,nl),bl=lift(bv,nl);double[] means=new double[12];int validLong=0;
        double[][] all=new double[nl][12];
        for(int k=0;k<nl;k++) {
            if(!tonal(s,true,al.get(k).index)||!tonal(s,true,bl.get(k).index))continue;
            validLong++;for(int r=0;r<12;r++){all[k][r]=tv(tone(s,true,al.get(k).index),tone(s,true,bl.get(k).index),r);means[r]+=all[k][r];}
        }
        if(validLong*4L<nl*3L)return rejected(SimilarityRejectionReason.INSUFFICIENT_VALID_BINS);
        for(int r=0;r<12;r++)means[r]/=validLong;
        int rotation=0;for(int r=1;r<12;r++)if(means[r]<means[rotation]-EPS)rotation=r;
        int support=0;for(int k=0;k<nl;k++)if(tonal(s,true,al.get(k).index)&&tonal(s,true,bl.get(k).index)) {
            double min=Arrays.stream(all[k]).min().orElseThrow();if(all[k][rotation]<=min+EPS)support++;
        }
        if(rotation!=0&&support*5L<validLong*4L)return detailRejected(SimilarityRejectionReason.AMBIGUOUS,means,rotation,validLong,support);
        View as=view(s,false,a),bs=view(s,false,b);int n=Math.max(as.cells.size(),bs.cells.size());
        if(n>MAX_REFERENCE_N)throw new IllegalArgumentException("bounded test oracle, not product duration rejection");
        List<Cell> first=lift(as,n),second=lift(bs,n);boolean[] nominal=new boolean[n];
        Observation[] fa=new Observation[n],fb=new Observation[n];
        for(int i=0;i<n;i++){fa[i]=observe(s,first.get(i));fb[i]=observe(s,second.get(i));nominal[i]=local(fa[i],fb[i],rotation).valid;}
        int bins=0,run=0,maxRun=0;
        for(int bin=0;bin<32;bin++) {
            long area=0;for(int i=0;i<n;i++)if(nominal[i])area+=Math.max(0L,Math.min((bin+1L)*n,(i+1L)*32)-Math.max((long)bin*n,(long)i*32));
            if(4*area>=3L*n){bins++;run=0;}else maxRun=Math.max(maxRun,++run);
        }
        if(bins<24)return rejected(SimilarityRejectionReason.INSUFFICIENT_VALID_BINS);
        if(maxRun>4)return rejected(SimilarityRejectionReason.INVALID_BIN_RUN);
        int width=Math.min(n,(int)Math.ceil(.5*n/(Math.max(a.length(),b.length())/(double)s.format().sampleRateHz()))+2);
        double[][] cost=new double[n+1][n+1];byte[][] predecessor=new byte[n+1][n+1];
        for(double[] row:cost)Arrays.fill(row,Double.POSITIVE_INFINITY);cost[0][0]=0;
        for(int i=0;i<=n;i++)for(int j=0;j<=n;j++) {
            if(i==0&&j==0||!withinBand(i,j,n,a.length(),b.length(),s.format().sampleRateHz()))continue;
            double best=Double.POSITIVE_INFINITY;byte step=0;
            if(i>0&&j>0){Local l=local(fa[i-1],fb[j-1],rotation);best=cost[i-1][j-1]+Math.max(l.h,l.t);if(Double.isFinite(best))step=1;}
            if(i>0&&cost[i-1][j]+.5<best-EPS){best=cost[i-1][j]+.5;step=2;}
            if(j>0&&cost[i][j-1]+.5<best-EPS){best=cost[i][j-1]+.5;step=3;}
            cost[i][j]=best;predecessor[i][j]=step;
        }
        if(!Double.isFinite(cost[n][n]))return rejected(SimilarityRejectionReason.AMBIGUOUS);
        List<Step> reverse=new ArrayList<>();int i=n,j=n;
        while(i>0||j>0) {
            byte step=predecessor[i][j];if(step==0)throw new IllegalStateException("reference unreachable backtrace");
            Local l=step==1?local(fa[i-1],fb[j-1],rotation):new Local(.5,.5,true,Double.NaN);
            reverse.add(new Step(i,j,step,l.h,l.t,Math.max(l.h,l.t)));
            if(step!=3)i--;if(step!=2)j--;
        }
        Collections.reverse(reverse);double sh=0,st=0,sd=0;for(Step p:reverse){sh+=p.h;st+=p.t;sd+=p.cost;}
        double rho=bins/32d,h=rho*clamp(1-sh/n-(rotation==0?0:.05)),t=rho*clamp(1-st/n),alignment=rho*clamp(1-sd/n);
        return new Score(h,t,alignment,Math.min(h,Math.min(t+.05,alignment+.03)),rotation,bins,SimilarityRejectionReason.NONE,means,validLong,support,n,width,maxRun,List.copyOf(reverse),first,second);
    }
    static Comparison compare(Source s,Range a,Range b) {
        Score central=score(s,a,b);List<Variant> variants=new ArrayList<>();SimilarityRejectionReason reason=central.reason;
        long delta=Math.round(.5*s.format().sampleRateHz());
        for(int slot=0;slot<8;slot++) {
            Range original=slot<4?a:b;long start=original.start,end=original.end;
            switch(slot%4){case 0->start=Math.max(0,start-delta);case 1->start=Math.min(s.format().frames(),start+delta);case 2->end=Math.max(0,end-delta);case 3->end=Math.min(s.format().frames(),end+delta);}
            Range changed=new Range(start,end),va=slot<4?changed:a,vb=slot<4?b:changed;
            Score v=start>=end?rejected(SimilarityRejectionReason.UNSTABLE_BOUNDARY):score(s,va,vb);
            variants.add(new Variant(slot,va,vb,v));
            if(central.reason==SimilarityRejectionReason.NONE&&!stable(central,v))reason=SimilarityRejectionReason.UNSTABLE_BOUNDARY;
        }
        return new Comparison(central,List.copyOf(variants),reason);
    }
    static Score rejected(SimilarityRejectionReason r){return detailRejected(r,new double[12],0,0,0);}
    static boolean withinBand(int i,int j,int n,long durationA,long durationB,int fs) {
        if(n<=0||durationA<=0||durationB<=0||fs<=0||i<0||j<0||i>n||j>n)throw new IllegalArgumentException("prefix/range");
        BigInteger displacement=BigInteger.valueOf(2L*Math.abs(i-j));
        BigInteger bound=BigInteger.valueOf(n).multiply(BigInteger.valueOf(fs));
        return displacement.multiply(BigInteger.valueOf(durationA)).compareTo(bound)<=0
                &&displacement.multiply(BigInteger.valueOf(durationB)).compareTo(bound)<=0;
    }
    static boolean stable(Score central,Score variant){return variant.reason==SimilarityRejectionReason.NONE&&Math.abs(variant.a-central.a)<=.05&&variant.rotation==central.rotation;}
    static Score detailRejected(SimilarityRejectionReason r,double[] means,int rotation,int valid,int support){return new Score(0,0,0,0,rotation,0,r,means,valid,support,0,0,0,List.of(),List.of(),List.of());}
    static double clamp(double x){return Math.max(0,Math.min(1,x));}

    /** Selected-bin direct DFT. This has no FFT/vendor dependency and uses source support, not padding, for Hann. */
    static double[] dftPowers(float[] pcm,AudioFormat format,long center,int support,int fftLength,int[] bins) {
        if(support<2||fftLength<support)throw new IllegalArgumentException();format.validatePcm(pcm);
        double[] result=new double[bins.length];long start=center-(support-1L)/2;double h2=0;
        for(int n=0;n<support;n++){double h=.5-.5*Math.cos(2*Math.PI*n/(support-1d));h2+=h*h;}
        for(int ch=0;ch<format.channels();ch++)for(int z=0;z<bins.length;z++) {
            double re=0,im=0;
            for(int n=0;n<support;n++) {
                long at=start+n;double x=at<0||at>=format.frames()?0:pcm[Math.toIntExact(at*format.channels()+ch)];
                double h=.5-.5*Math.cos(2*Math.PI*n/(support-1d)),angle=2*Math.PI*bins[z]*n/fftLength;
                re+=x*h*Math.cos(angle);im-=x*h*Math.sin(angle);
            }
            result[z]+=(re*re+im*im)/(format.channels()*h2);
        }
        return result;
    }
    static double interpolate(double left,double center,double right,int k,int fs,int fftLength) {
        double floor=center*1e-12,a=Math.log(Math.max(left,floor))/2,b=Math.log(Math.max(center,floor))/2,c=Math.log(Math.max(right,floor))/2;
        double denom=a-2*b+c,delta=denom==0?0:.5*(a-c)/denom;delta=Math.max(-.5,Math.min(.5,delta));return(k+delta)*fs/fftLength;
    }
}
