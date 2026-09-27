import com.quickmaster.ui.MainController;
import java.lang.reflect.Method;
import java.util.Locale;

/** Whole supported rate matrix: coherent quadrature and residual, including SRC images. */
public class ExportSrcBandAudit {
    public static void main(String[] args) throws Exception {
        Method convert=MainController.class.getDeclaredMethod("resampleForExport",float[].class,int.class,int.class,int.class);
        convert.setAccessible(true);
        int cases=0, failures=0; double maxDroop=0, maxResidual=-999, maxAlias=-999;
        for(int from:new int[]{44100,48000,88200,96000,176400,192000})
            for(int to:new int[]{44100,48000,88200,96000,176400,192000}) {
                if(from==to)continue;
                for(int hz:new int[]{32,1000,18000,20000,to/2+500}) {
                    if(hz>=from/2)continue;
                    float[] in=new float[from];
                    for(int f=0;f<from;f++)in[f]=(float)(.5*Math.sin(2*Math.PI*hz*f/from+.37));
                    float[] out=(float[])convert.invoke(null,in,1,from,to);
                    int first=to/4,last=3*to/4,n=last-first;
                    double sine=0,cosine=0,power=0;
                    for(int f=first;f<last;f++) {
                        double w=2*Math.PI*hz*f/to;
                        sine+=out[f]*Math.sin(w);cosine+=out[f]*Math.cos(w);power+=(double)out[f]*out[f];
                    }
                    double db=20*Math.log10(2*Math.hypot(sine,cosine)/n/.5);
                    double residual=0;
                    for(int f=first;f<last;f++) {
                        double w=2*Math.PI*hz*f/to;
                        double error=out[f]-2*(sine*Math.sin(w)+cosine*Math.cos(w))/n;
                        residual+=error*error;
                    }
                    double residualDb=10*Math.log10(residual/(n*.125));
                    double aliasDb=10*Math.log10(power/(n*.125));
                    boolean pass=hz<to/2 ? Math.abs(db)<.1 && residualDb< -80 : aliasDb< -90;
                    if(hz<to/2){maxDroop=Math.max(maxDroop,Math.abs(db));maxResidual=Math.max(maxResidual,residualDb);}
                    else maxAlias=Math.max(maxAlias,aliasDb);
                    if(!pass){failures++;System.out.printf(Locale.ROOT,"FAIL from=%d to=%d hz=%d gain=%.9f residual=%.3f alias=%.3f%n",from,to,hz,db,residualDb,aliasDb);}
                    cases++;
                }
            }
        System.out.printf(Locale.ROOT,"EXPORT_SRC_BAND cases=%d failures=%d maxAbsGainErrorDb=%.9f maxResidualDb=%.3f maxAliasDb=%.3f%n",cases,failures,maxDroop,maxResidual,maxAlias);
        if(failures!=0)System.exit(1);
    }
}
