import com.quickmaster.ui.MainController;
import java.lang.reflect.Method;
import java.util.*;

/** Independent coherent interior sine projection for supported high-rate deliveries. */
public class ExportSrcSpectralAudit {
    public static void main(String[] args)throws Exception{
        Method method=MainController.class.getDeclaredMethod("resampleForExport",float[].class,int.class,int.class,int.class);method.setAccessible(true);
        int failures=0;
        for(int from:new int[]{96000,176400,192000})for(int to:new int[]{44100,48000})for(int hz:new int[]{1000,18000,20000,28000,35000}){
            float[] in=new float[from];for(int f=0;f<in.length;f++)in[f]=(float)(.5*Math.sin(2*Math.PI*hz*f/from));
            float[] out=(float[])method.invoke(null,in,1,from,to);
            double power=0;int first=to/4,last=3*to/4;
            for(int f=first;f<last;f++)power+=(double)out[f]*out[f];
            double db=20*Math.log10(Math.sqrt(2*power/(last-first))/.5);
            boolean pass=hz<to/2?Math.abs(db)<.1:db< -90;
            if(!pass)failures++;
            System.out.printf(Locale.ROOT,"EXPORT_SRC_SPECTRAL from=%d to=%d hz=%d gainDb=%.6f pass=%s%n",from,to,hz,db,pass);
        }
        System.out.println("EXPORT_SRC_SPECTRAL failures="+failures);if(failures>0)System.exit(1);
    }
}
