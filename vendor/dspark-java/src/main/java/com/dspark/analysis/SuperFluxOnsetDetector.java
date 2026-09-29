package com.dspark.analysis;

import com.dspark.core.FFTReal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

/**
 * Offline SuperFlux path of DSPark C++ OnsetDetector, pinned at 474b7d1.
 * Quarter-tone triangular bands, log magnitude, three-band maximum reference,
 * and the symmetric Boeck peak picker. Periodic Hann, automatic time-span FFT,
 * 200 Hz hop and 0.34*N localisation are the upstream conventions.
 * Stereo extends the mono front end by pooling spectral power, never waveform
 * downmixing. No streaming/ComplexDomain/whitening parity claim is made here.
 */
public final class SuperFluxOnsetDetector {
    public record Result(double[] times, double[] durations, double[] novelty, int fftSize, int hop) { }
    private record Band(int start, double[] weights) { }

    public Result analyze(float[] input,int channels,double rate,double delta) {
        if(input==null || (channels!=1 && channels!=2) || input.length%channels!=0
                || !Double.isFinite(rate) || rate<=0 || !Double.isFinite(delta) || delta<=0)
            throw new IllegalArgumentException("Invalid SuperFlux input.");
        // Validate the complete source, including the final incomplete hop.
        for(int i=0;i<input.length;i++) {
            if((i&16383)==0 && Thread.currentThread().isInterrupted())throw new CancellationException();
            if(!Float.isFinite(input[i]))throw new IllegalArgumentException("Non-finite SuperFlux sample.");
        }
        int size=512;while(size<16384 && size<rate*2048/48000)size*=2;
        int hop=Math.max(1,Math.min(size,(int)Math.round(rate/200)));
        int frames=input.length/channels,rows=frames/hop;
        Band[] bands=filterbank(size,rate);
        FFTReal fft=new FFTReal(size);
        double[] window=new double[size];
        for(int i=0;i<size;i++)window[i]=.5-.5*Math.cos(2*Math.PI*i/size);
        float[] time=new float[size],spectrum=new float[size+2];
        double[] magnitudes=new double[size/2+1],current=new double[bands.length],previous=new double[bands.length];
        double[] novelty=new double[rows];
        for(int row=0;row<rows;row++) {
            if(Thread.currentThread().isInterrupted())throw new CancellationException();
            Arrays.fill(magnitudes,0);
            int first=(row+1)*hop-size;
            for(int channel=0;channel<channels;channel++) {
                for(int i=0;i<size;i++) {
                    float value=first+i<0?0:input[(first+i)*channels+channel];
                    time[i]=(float)(value*window[i]);
                }
                fft.forward(time,spectrum);
                for(int k=0;k<magnitudes.length;k++) {
                    double re=spectrum[2*k],im=spectrum[2*k+1];magnitudes[k]+=re*re+im*im;
                }
            }
            for(int k=0;k<magnitudes.length;k++)magnitudes[k]=Math.sqrt(magnitudes[k]/channels);
            double flux=0;
            for(int b=0;b<bands.length;b++) {
                Band band=bands[b];double sum=0;
                for(int i=0;i<band.weights.length;i++)sum+=magnitudes[band.start+i]*band.weights[i];
                current[b]=Math.log10(1+sum*2048/size);
                double reference=previous[b];
                if(b>0)reference=Math.max(reference,previous[b-1]);
                if(b+1<bands.length)reference=Math.max(reference,previous[b+1]);
                flux+=Math.max(0,current[b]-reference);
            }
            novelty[row]=flux/Math.max(1,bands.length);
            double[] swap=previous;previous=current;current=swap;
        }
        int maxRadius=milliseconds(30,rate,hop),preAverage=milliseconds(100,rate,hop);
        int postAverage=milliseconds(70,rate,hop),warmup=size/hop+2;
        int[] selected=new int[rows];int count=0,last=-1_000_000;
        for(int row=warmup;row<rows;row++) {
            boolean maximum=true;
            for(int j=Math.max(0,row-maxRadius);j<=Math.min(rows-1,row+maxRadius);j++)
                if(novelty[j]>novelty[row]) {maximum=false;break;}
            if(!maximum || row-last<=maxRadius)continue;
            int first=Math.max(0,row-preAverage),end=Math.min(rows-1,row+postAverage);
            double sum=0;for(int j=first;j<=end;j++)sum+=novelty[j];
            if(novelty[row]<sum/(end-first+1)+delta)continue;
            selected[count++]=row;last=row;
        }
        double[] times=new double[count],durations=new double[count];
        int offset=(int)Math.round(.34*size),maxDuration=milliseconds(120,rate,hop);
        for(int k=0;k<count;k++) {
            int row=selected[k],end=row;
            times[k]=((long)(row+1)*hop-size/2+offset)/rate;
            for(int j=row+1;j<rows && j-row<=maxDuration;j++) {end=j;if(novelty[j]<=.35*novelty[row])break;}
            durations[k]=Math.max(.02,Math.min(.12,(end-row)*hop/rate));
        }
        return new Result(times,durations,novelty,size,hop);
    }

    private static int milliseconds(double ms,double rate,int hop) {return Math.max(1,(int)Math.round(ms*.001*rate/hop));}
    private static Band[] filterbank(int size,double rate) {
        ArrayList<Integer> centres=new ArrayList<>();double binHz=rate/size,max=Math.min(16000,rate*.5*.999);
        for(int i=0;;i++) {
            double frequency=27.5*Math.pow(2,i/24.0);if(frequency>max)break;
            int bin=Math.max(0,Math.min(size/2,(int)Math.round(frequency/binHz)));
            if(centres.isEmpty() || bin>centres.get(centres.size()-1))centres.add(bin);
        }
        ArrayList<Band> bands=new ArrayList<>();
        for(int i=1;i+1<centres.size();i++) {
            int lo=centres.get(i-1),ce=centres.get(i),hi=centres.get(i+1);double[] weights=new double[hi-lo+1];
            for(int k=lo;k<=hi;k++)weights[k-lo]=k<=ce?(k-lo)/(double)(ce-lo):(hi-k)/(double)(hi-ce);
            bands.add(new Band(lo,weights));
        }
        return bands.toArray(Band[]::new);
    }
}
