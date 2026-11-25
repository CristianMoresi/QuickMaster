package com.quickmaster.processing;

import com.dspark.effects.MasterEqualizer;
import com.quickmaster.audio.WavFile;
import com.quickmaster.processing.eq.EqualizerProcessor;
import com.quickmaster.processing.dynamics.leveler.CancellationToken;
import com.quickmaster.processing.dynamics.leveler.FiniteTruePeakStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreviewWindowRendererTest {
    @Test void largerOversampledPreviewBlocksPreservePreparedDownstreamChain() {
        int rate=48000;float[] x=signal(rate,2);var p=chain(false,MasterEqualizer.BandPhase.LINEAR,MasterEqualizer.Channel.MID);
        p.removeProcessor(p.getProcessors().get(p.getProcessors().size()-1));
        var peak=new com.quickmaster.processing.dynamics.PeakCompProcessor();peak.setEnabled(true);peak.setTargetDb(-2);p.addProcessor(peak);
        var clip=new com.quickmaster.processing.clip.SoftClipProcessor();clip.setEnabled(true);clip.setSatDb(2);p.addProcessor(clip);
        var multi=new com.quickmaster.processing.limit.MultibandLimiterProcessor();multi.setEnabled(true);
        for(int i=0;i<4;i++)multi.setPushDb(i,1.5);p.addProcessor(multi);
        var broad=new com.quickmaster.processing.limit.BroadbandLimiterProcessor();broad.setEnabled(true);broad.setPushDb(2);p.addProcessor(broad);
        var n=new PeakNormalizer();n.setEnabled(false);p.addProcessor(n);
        var file=new WavFile("reference",rate,2,x,32,true);p.processOversampled(file,4,null);
        var preview=new PreviewWindowRenderer(p,x,rate,2,4);float[] out=preview.render(rate,rate/4,new CancellationToken());
        double max=0;for(int i=0;i<out.length;i++)max=Math.max(max,Math.abs(out[i]-file.getSamples()[rate*2+i]));
        assertTrue(max<3e-5,"Prepared full-chain preview diverges: "+max);
    }
    static float[] signal(int rate, int channels) {
        float[] x = new float[rate * 3 * channels];
        for (int i = 0; i < x.length / channels; i++) for (int c = 0; c < channels; c++)
            x[i * channels + c] = (float)((.3 * Math.sin(i * 2 * Math.PI * 857 / rate)
                    + .14 * Math.sin(i * 2 * Math.PI * 101 / rate)) * (c == 0 ? 1 : -.7));
        return x;
    }
    static ProcessingPipeline chain(boolean auto, MasterEqualizer.BandPhase phase, MasterEqualizer.Channel route) {
        var p = new ProcessingPipeline(); var e = new EqualizerProcessor();
        e.setNumBands(1); e.setAutoGainEnabled(auto); var b = new MasterEqualizer.Band();
        b.gainDb = 18.8; b.frequency = 857; b.q = .71; b.phase = phase; b.channel = route; e.setBand(0, b);
        p.addProcessor(e); var n = new PeakNormalizer(); n.setEnabled(false); p.addProcessor(n); return p;
    }
    @Test void staticPreviewMatchesColdEqAtSourceClockAcrossPhasesRoutesAndOversampling() {
        int rate = 48000;
        for (var phase : MasterEqualizer.BandPhase.values()) for (var route : MasterEqualizer.Channel.values())
            for (int factor : new int[]{1, 4}) {
                float[] x = signal(rate, 2), saved = x.clone();
                var p = chain(false, phase, route);
                var file = new WavFile("fixture", rate, 2, x, 32, true); p.processOversampled(file, factor, null);
                var preview = new PreviewWindowRenderer(chain(false, phase, route), x, rate, 2, factor);
                float[] out = preview.render(rate, rate / 4, new CancellationToken());
                double max = 0;
                for (int i = 0; i < out.length; i++) max = Math.max(max, Math.abs(out[i] - file.getSamples()[rate * 2 + i]));
                assertTrue(max < 3e-5, phase + " " + route + " OS=" + factor + " error=" + max);
                assertArrayEquals(saved, x);
            }
    }
    @Test void autoGainWindowIsOneLinkedScalarSafeWithoutClippedSamples() {
        for (int rate : new int[]{44100, 48000, 96000}) for (int channels : new int[]{1, 2})
            for (int factor : new int[]{1, 2, 4, 8, 16}) {
                float[] x = signal(rate, channels);
                var raw = new PreviewWindowRenderer(chain(false, MasterEqualizer.BandPhase.LINEAR, MasterEqualizer.Channel.STEREO), x, rate, channels, factor);
                var safe = new PreviewWindowRenderer(chain(true, MasterEqualizer.BandPhase.LINEAR, MasterEqualizer.Channel.STEREO), x, rate, channels, factor);
                float[] a = raw.render(rate, rate / 8, new CancellationToken()), b = safe.render(rate, rate / 8, new CancellationToken());
                int max = 0; for (int i = 1; i < a.length; i++) if (Math.abs(a[i]) > Math.abs(a[max])) max = i;
                double gain = b[max] / (double)a[max];
                for (int i = 0; i < b.length; i++) assertEquals(a[i] * gain, b[i], 3e-6);
                var peak = new FiniteTruePeakStream(channels, true); peak.accept(b, 0, b.length / channels);
                assertTrue(peak.finish() < 1); assertTrue(gain < .5);
            }
    }
    static float[] programme(int rate, int channels, double seconds) {
        var random = new java.util.Random(41); float[] x = new float[(int)(rate * seconds) * channels];
        for (int i = 0; i < x.length / channels; i++) {
            double v = .12 * random.nextGaussian() + .1 * Math.sin(i * 2 * Math.PI * 2300 / rate);
            for (int c = 0; c < channels; c++) x[i * channels + c] = (float)(c == 0 ? v : .8 * v + .02 * random.nextGaussian());
        }
        return x;
    }
    static ProcessingPipeline autoEqChain(double amount, double cutBoost) {
        var p = new ProcessingPipeline(); var auto = new com.quickmaster.processing.eq.AutoEqProcessor();
        auto.setEnabled(true); auto.setAmount(amount); auto.setCutBoost(cutBoost); p.addProcessor(auto);
        var n = new PeakNormalizer(); n.setEnabled(false); p.addProcessor(n); return p;
    }
    @Test void autoEqOfNewSettingsIsAuditionedOnTheWindowAtOnce() {
        int rate = 48000;
        for (int channels : new int[]{1, 2}) for (int factor : new int[]{1, 4}) {
            float[] x = programme(rate, channels, 8), saved = x.clone();
            var whole = new WavFile("reference", rate, channels, x.clone(), 32, true);
            autoEqChain(1, 0).processOversampled(whole, factor, null);
            // No render of these settings yet: the window is balanced on its own.
            var preview = new PreviewWindowRenderer(autoEqChain(1, 0), x, rate, channels, factor);
            int start = 5 * rate, frames = rate / 2;
            float[] out = preview.render(start, frames, new CancellationToken());
            double error = 0, change = 0;
            for (int i = 0; i < out.length; i++) {
                error += Math.pow(out[i] - whole.getSamples()[start * channels + i], 2);
                change += Math.pow(whole.getSamples()[start * channels + i] - x[start * channels + i], 2);
            }
            assertTrue(change > 0, "The whole render must change the fixture");
            assertTrue(error < .12 * change, "channels=" + channels + " OS=" + factor + " window is "
                    + 10 * Math.log10(error / change) + " dB under the Auto EQ's own change");
            // The following window is rendered afresh, not replayed from the first one.
            float[] next = preview.render(start + frames - rate / 10, frames, new CancellationToken());
            double later = 0, laterChange = 0; int at = (start + frames - rate / 10) * channels;
            for (int i = 0; i < next.length; i++) {
                later += Math.pow(next[i] - whole.getSamples()[at + i], 2);
                laterChange += Math.pow(whole.getSamples()[at + i] - x[at + i], 2);
            }
            assertTrue(later < .12 * laterChange, "second window OS=" + factor + ": " + 10 * Math.log10(later / laterChange) + " dB");
            assertArrayEquals(saved, x);
        }
    }
    @Test void autoEqHoldingTheRenderOfItsSettingsIsPlayedByPosition() {
        int rate = 48000; float[] x = programme(rate, 2, 6);
        var chain = autoEqChain(.5, 0);
        var whole = new WavFile("reference", rate, 2, x.clone(), 32, true); chain.processOversampled(whole, 1, null);
        var auto = (com.quickmaster.processing.eq.AutoEqProcessor)chain.getProcessors().get(0);
        assertFalse(auto.awaitsRender());
        float[] out = new PreviewWindowRenderer(chain, x, rate, 2, 1).render(3 * rate, rate / 4, new CancellationToken());
        for (int i = 0; i < out.length; i++) assertEquals(whole.getSamples()[3 * rate * 2 + i], out[i], 0f);
        // Switched off, it is not heard at all.
        auto.setEnabled(false);
        float[] dry = new PreviewWindowRenderer(chain, x, rate, 2, 1).render(3 * rate, rate / 4, new CancellationToken());
        for (int i = 0; i < dry.length; i++) assertEquals(x[3 * rate * 2 + i], dry[i], 0f);
    }
    @Test void downstreamAnalysisNeverRunsAndAbsoluteEnvelopesRemainAligned() {
        int rate = 48000; float[] x = signal(rate, 1); var p = new ProcessingPipeline();
        p.addProcessor(new AudioProcessor() {
            long position;
            public void prepare(int r, long n) { assertEquals(x.length, n); }
            public boolean usesAnalysis() { return true; }
            public void analyze(float[] pcm, int ch) { fail("Preview must not analyze the song"); }
            public void setPlaybackPosition(long frame) { position = frame; }
            public float[] process(float[] pcm, int ch) { for (int i=0;i<pcm.length;i++) pcm[i] *= (float)((position++ % 1000) / 1000.0); return pcm; }
            public boolean isEnabled() { return true; } public void setEnabled(boolean b) { }
        });
        var preview = new PreviewWindowRenderer(p, x, rate, 1, 1);
        float[] out = preview.render(rate + 217, 1111, new CancellationToken());
        for (int i=0;i<out.length;i++) assertEquals(x[rate+217+i]*(float)(((rate+217+i)%1000)/1000.0),out[i]);
        var token = new CancellationToken(); token.cancel();
        assertThrows(java.util.concurrent.CancellationException.class, () -> preview.render(0, 111, token));
    }
}
