package com.dspark.effects;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** Actual MSVC output from unmodified upstream 474b7d1, not Java-generated expectations. */
class ClipUpstreamVectorsTest {
    @Test void matchesPinnedNativeTransferVectors() throws Exception {
        var stream=getClass().getResourceAsStream("/clipper-cpp-474b7d1.txt");assertNotNull(stream);
        Clipper clip=new Clipper();int count=0;
        try(var lines=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))) {
            String line;
            while((line=lines.readLine())!=null) {
                String[] f=line.split(" ");clip.setMode(Clipper.Mode.values()[Integer.parseInt(f[0])]);
                double ceiling=Double.parseDouble(f[1]),input=Double.parseDouble(f[2]),expected=Double.parseDouble(f[3]);
                // C++ Analog uses its documented 3.73e-9-error Remez fastSin;
                // Java uses full-precision Math.sin. Other curves remain 3e-14.
                double tolerance=clip.getMode()==Clipper.Mode.ANALOG?4e-9:3e-14;
                assertEquals(expected,clip.shapeSample(input,ceiling),ceiling*tolerance,line);count++;
            }
        }
        assertEquals(6416,count);
    }
}
