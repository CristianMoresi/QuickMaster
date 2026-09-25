package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;
import com.quickmaster.processing.dynamics.leveler.model.ChannelLayout;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;

/** The frozen pre-change PRODUCT implementation is an independent raw-bits oracle. */
class LoudnessCoreChunkEquivalenceTest
{
    static final String BASELINE_SHA = "971947a18a928dcae0cf47912523f0d564c9146bee2885ff0820b9ca3c2ba851";
    static final double[] POWERS = {0d,-0d,Double.MIN_VALUE,3*Double.MIN_VALUE,1e-240,1e-20,.08,.5,1e60};

    @Test void baselineSourceIsPinnedAndBaselineAgainstItselfClosesTheOracle() throws Exception
    {
        String source = Files.readString(Path.of("src/test/java/com/quickmaster/processing/dynamics/leveler/LoudnessCoreBaseline.java"))
                .replace("LoudnessCoreBaseline", "LoudnessCore").replace("\r\n", "\n");
        assertEquals(BASELINE_SHA, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))));
        verifySynthetic(LoudnessCoreBaseline.class, false);
    }

    @Test void eachSmallUpdateAllNodesRangesAndWrapsMatchFrozenBits() throws Exception
    { verifySynthetic(LoudnessCore.class, false); }

    @Test void chunkEdgesOddMixedNodesAndSignedZeroMatchFrozenBits() throws Exception
    { verifySynthetic(LoudnessCore.class, true); }

    static void verifySynthetic(Class<?> candidate, boolean edges) throws Exception
    {
        int[] sizes = edges ? new int[] {32767,32768,32769,65535,65536,65537}
                : java.util.stream.IntStream.rangeClosed(1,41).toArray();
        for (int n : sizes)
        {
            Tree old = new Tree(LoudnessCoreBaseline.class, n), current = new Tree(candidate, n);
            compareNodes(old, current);
            int updates = edges ? 3 * 17 : 3 * n;
            for (int step = 0; step < updates; step++)
            {
                int leaf = edges ? new int[] {0,1,2,n/2-1,n/2,n/2+1,32765,32766,32767,32768,32769,n-4,n-3,n-2,n-1,0,1}[step%17] :
                        step < n ? step : step < 2*n ? 2*n-1-step : (step-n)*17 % n;
                leaf = Math.floorMod(leaf,n);
                double power = POWERS[step%POWERS.length];
                old.set(leaf,power); current.set(leaf,power);
                old.call("rebuildTree",leaf); current.call("rebuildTree",leaf);
                if (!edges) compareNodes(old,current);
                else for (int node : new int[] {1,(n+1)/2-1,(n+1)/2,n-1,n,2*n-1})
                    if (node>0) assertEquals(bits(old.call("nodePower",node)), bits(current.call("nodePower",node)), "EDGE_NODE n="+n+" node="+node);
                int stride = edges ? 4093 : 1;
                for (int start=0;start<=n;start+=stride)
                    for (int end=start;end<=n;end+=stride)
                        assertEquals(bits(old.call("treeRange",start,end)), bits(current.call("treeRange",start,end)), "RANGE n="+n+" start="+start+" end="+end);
                for (int end=0;end<n;end+=stride)
                    for (int length=0;length<=n;length+=stride)
                        assertEquals(bits(old.call("treeWindow",end,length)), bits(current.call("treeWindow",end,length)), "WINDOW n="+n+" end="+end+" length="+length);
                assertEquals(0L, bits(read(current.tree,0)), "RESERVED_TREE_ZERO");
            }
            compareNodes(old,current);
        }
    }

    static void compareNodes(Tree old, Tree current) throws Exception
    {
        for (int i=0;i<old.n;i++) assertEquals(bits(read(old.ring,i)),bits(read(current.ring,i)),"RING_LEAF n="+old.n+" i="+i);
        for (int node=1;node<2*old.n;node++)
            assertEquals(bits(old.call("nodePower",node)),bits(current.call("nodePower",node)),"LOGICAL_NODE n="+old.n+" node="+node);
    }

    @Test void outputsAndLogicalStateMatchEveryFrameForLayoutsRatesAndAdversarialDynamics() throws Exception
    {
        for (int rate : new int[] {1,3,29,31,500,44100,48000,96000})
            for (ChannelLayout layout : ChannelLayout.values())
            {
                LoudnessCoreBaseline old = new LoudnessCoreBaseline(rate,layout);
                LoudnessCore current = new LoudnessCore(rate,layout);
                int n=3*rate,m=Math.max(1,(int)StrictMath.round(.4d*rate));
                float[] frame = new float[layout.channels()];
                int frames = (rate<1000 ? 4*n : n)+17;
                Field[] oldFields=LoudnessCoreBaseline.class.getDeclaredFields(), fields=LoudnessCore.class.getDeclaredFields();
                for (Field f:oldFields)f.setAccessible(true);for(Field f:fields)f.setAccessible(true);
                Object oldRing=oldFields[9].get(old),ring=fields[9].get(current);
                for(int i=0;i<frames;i++)
                {
                    for(int c=0;c<frame.length;c++) frame[c]= i%97==0 ? -0f : (float)(StrictMath.sin(i*.173+c*.39)*(i<n/5?.5:1e-10));
                    if(i==0)frame[0]=.75f;
                    old.acceptFrame(frame,0);current.acceptFrame(frame,0);
                    assertEquals(old.framesSeen(),current.framesSeen());
                    assertEquals(bits(read(oldRing,i%n)),bits(read(ring,i%n)),"FRAME_INSERTED_POWER");
                    // All scalar/filter state, and complete logical storage at every small frame or large boundary.
                    for(int f=0;f<fields.length;f++) if(f!=9&&f!=10) compareValue(oldFields[f].get(old),fields[f].get(current));
                    if(rate<1000 || i%32768==0 || i==frames-1)
                    {
                        for(int j=0;j<n;j++)assertEquals(bits(read(oldRing,j)),bits(read(ring,j)));
                        double[] expected=(double[])oldFields[10].get(old);Object actual=fields[10].get(current);
                        for(int j=1;j<n;j++) assertEquals(bits(expected[j]),bits(logical(ring,actual,n,j)),"FRAME_LOGICAL_TREE");
                    }
                    if(i+1>=m) assertEquals(bits(old.momentaryPower()),bits(current.momentaryPower()));
                    if(i+1>=n) assertEquals(bits(old.shortTermPower()),bits(current.shortTermPower()));
                }
                for(float bad:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY})
                {
                    frame[frame.length-1]=bad;long seen=current.framesSeen();
                    assertThrows(IllegalArgumentException.class,()->old.acceptFrame(frame,0));
                    assertThrows(IllegalArgumentException.class,()->current.acceptFrame(frame,0));
                    assertEquals(seen,current.framesSeen());
                    for(int f=0;f<fields.length;f++)if(f!=9&&f!=10)compareValue(oldFields[f].get(old),fields[f].get(current));
                }
            }
    }

    private static void compareValue(Object expected,Object actual)
    {
        if(expected instanceof double[] a){double[] b=(double[])actual;assertEquals(a.length,b.length);for(int i=0;i<a.length;i++)assertEquals(bits(a[i]),bits(b[i]));}
        else if(expected instanceof Double d)assertEquals(bits(d),bits((Double)actual));else assertEquals(expected,actual);
    }
    static double logical(Object ring,Object tree,int n,int node)
    {
        if(node>=n)return read(ring,node-n);
        if(tree instanceof double[])return read(tree,node);
        int k=(n+1)/2;
        return node<k?read(tree,node):read(ring,2*node-n)+read(ring,2*node+1-n);
    }
    static double read(Object storage,int index){return storage instanceof double[] flat?flat[index]:((double[][])storage)[index/32768][index%32768];}
    static long bits(Object value){return Double.doubleToRawLongBits((Double)value);}

    static final class Tree
    {
        final int n;final Object ring,tree;final boolean chunks;final Map<String,Method> methods=new HashMap<>();
        Tree(Class<?> type,int n)throws Exception
        {
            this.n=n;chunks=type.getDeclaredField("weightedPowerRing").getType()==double[][].class;
            ring=chunks?pages(n):new double[n];tree=chunks?pages((n+1)/2):new double[n];
            for(String name:List.of("nodePower","rebuildTree","treeRange","treeWindow"))
            {
                boolean range=name.equals("treeRange")||name.equals("treeWindow");
                Class<?>[] params=new Class<?>[(chunks?3:2)+(range?2:1)];params[0]=params[1]=chunks?double[][].class:double[].class;
                Arrays.fill(params,2,params.length,int.class);Method method=type.getDeclaredMethod(name,params);method.setAccessible(true);methods.put(name,method);
            }
        }
        void set(int index,double value){if(chunks)((double[][])ring)[index/32768][index%32768]=value;else((double[])ring)[index]=value;}
        Object call(String name,int...values)throws Exception
        {
            Object[] args=new Object[(chunks?3:2)+values.length];args[0]=ring;args[1]=tree;int at=2;if(chunks)args[at++]=n;for(int i:values)args[at++]=i;
            try{return methods.get(name).invoke(null,args);}catch(InvocationTargetException failure){if(failure.getCause() instanceof RuntimeException e)throw e;throw failure;}
        }
    }
    static double[][] pages(int n){double[][] out=new double[(n-1)/32768+1][];for(int i=0;i<out.length;i++)out[i]=new double[Math.min(32768,n-i*32768)];return out;}
}
