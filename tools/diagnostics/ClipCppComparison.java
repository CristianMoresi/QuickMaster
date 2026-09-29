import com.dspark.effects.Clipper;
import java.nio.file.*;
import java.util.Locale;

class ClipCppComparison {
    public static void main(String[] args) throws Exception {
        Clipper clip=new Clipper();int count=0;double maxError=0;
        for(String line:Files.readAllLines(Path.of(args[0]))) {
            String[] fields=line.split(" ");clip.setMode(Clipper.Mode.values()[Integer.parseInt(fields[0])]);
            double ceiling=Double.parseDouble(fields[1]),input=Double.parseDouble(fields[2]),expected=Double.parseDouble(fields[3]);
            double actual=clip.shapeSample(input,ceiling),relative=Math.abs(actual-expected)/ceiling;
            maxError=Math.max(maxError,relative);count++;
            // DSPark C++ fastSin is a degree-9 Remez approximation, documented
            // max error 3.73e-9. Java uses Math.sin, NOT that approximation.
            // Keep the 3e-14 limit for every other curve, and the independently
            // specified approximation bound only for Analog.
            double tolerance=clip.getMode()==Clipper.Mode.ANALOG?4e-9:3e-14;
            if(relative>tolerance)throw new AssertionError("C++ mismatch "+line+" actual="+actual);
        }
        if(count!=6416)throw new AssertionError("Incomplete upstream fixture: "+count);
        System.out.printf(Locale.ROOT,"CLIP_CPP_PARITY_PASS vectors=%d maxErrorInCeilingUnits=%.12g%n",count,maxError);
    }
}
