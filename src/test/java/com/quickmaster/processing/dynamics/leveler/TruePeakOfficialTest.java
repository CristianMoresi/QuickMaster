package com.quickmaster.processing.dynamics.leveler;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Acquired EBU v5 originals: Tech 3341 section 2.9, table 1, cases 15 through 23. */
class TruePeakOfficialTest {
    private static Path corpus() {
        String root=System.getProperty("qm.ebuRoot");
        if(root==null||root.isBlank()||root.startsWith("${"))
            throw new IllegalStateException("Official true-peak tests require -Dqm.ebuRoot=<local EBU LTS root>; see docs/VALIDATION.md.");
        return Path.of(root).resolve("signals");
    }
    private static final String[] HASHES={
        "86f8a70eca1bc77623dd20a4acbadbc449711cd348615d852e3b0689827bea55",
        "9843c6fbc7c14d1bc5902ab23474851d766fd2ea92be5c45ab78ccd7e8a99ecd",
        "b4e7377b6c5c0c30bf68752f6d02f7b23909e40aed083a25902a550e0e1e2265",
        "d5fbe4854e93ef2a5a23260b522bdaa44bc739b69597bf751de919f11641d161",
        "31bda52dc66bb63aa0d04b2e5a1e6a4332296fa5635570469b2863d05a06d309",
        "a2df8791198781579a25e0437008ce9b1a48b13605a8b71886a360c4ceed16e4",
        "fc508ae04cb6d1dbcb286d6cdffe8e81a87effa9ba7bb267c17a5807d5b273d3",
        "d238255454a091b49592b25355738e200f59868c0804be5427c901ed10b3a910",
        "c7243e22970b82e020b45241c92d210cbf47217ccc967f014787a5f6400fa8a3"};

    @TestFactory Stream<DynamicTest> allNineOriginalsWithBothKernelsAndTwoPartitions() {
        return IntStream.rangeClosed(15,23).mapToObj(number->DynamicTest.dynamicTest("EBU original "+number,()->{
            String name="seq-3341-"+number+"-24bit.wav.wav";
            byte[] bytes=Files.readAllBytes(corpus().resolve(name));
            assertEquals(HASHES[number-15],HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),"Unmodified acquired original");
            int frames=number<20?153600:153604;
            assertEquals(44+frames*6,bytes.length);ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(0x46464952,b.getInt(0));assertEquals(bytes.length-8,b.getInt(4));assertEquals(0x45564157,b.getInt(8));
            assertEquals(0x20746d66,b.getInt(12));assertEquals(16,b.getInt(16));assertEquals(1,b.getShort(20));assertEquals(2,b.getShort(22));
            assertEquals(48000,b.getInt(24));assertEquals(288000,b.getInt(28));assertEquals(6,b.getShort(32));assertEquals(24,b.getShort(34));
            assertEquals(0x61746164,b.getInt(36));assertEquals(frames*6,b.getInt(40));
            float[] pcm=new float[frames*2];
            for(int i=0,offset=44;i<pcm.length;i++,offset+=3){int signed=(bytes[offset]&255)|((bytes[offset+1]&255)<<8)|(bytes[offset+2]<<16);pcm[i]=signed/8388608f;}
            double expected=number<19?-6:number==19?3:0,reference=-1;
            for(boolean fallback:new boolean[]{false,true})for(int chunk:new int[]{65536,997}) {
                var stream=new FiniteTruePeakStream(2,fallback);
                for(int from=0;from<frames;from+=chunk)stream.accept(pcm,from,Math.min(chunk,frames-from));
                double peak=stream.finish(),db=20*StrictMath.log10(peak);
                assertEquals(frames,stream.framesAccepted());assertEquals(6,stream.tailFrames());assertEquals(peak,stream.finish(),0);
                assertTrue(Double.isFinite(db));assertTrue(db>=expected-.4&&db<=expected+.2,"Original "+number+" measured "+db);
                if(reference<0)reference=peak;else assertEquals(Double.doubleToLongBits(reference),Double.doubleToLongBits(peak));
                System.out.println("TP_OFFICIAL case="+number+" fileSha256="+HASHES[number-15]+" rate=48000 channels=2 frames="+frames+" fallback="+fallback+" chunk="+chunk+" peak="+peak+" dBTP="+db+" lower="+(expected-.4)+" upper="+(expected+.2)+" tailFrames="+stream.tailFrames());
            }
        }));
    }
}
