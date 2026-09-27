package com.quickmaster.playback;

import com.quickmaster.audio.WavFile;
import com.quickmaster.processing.*;
import org.junit.jupiter.api.Test;
import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class AudioPlayerSessionTest {
    @Test void approvedPcmSkipsMutableDspAndHistoryRecalculationOnSeek() throws Exception {
        ProcessingPipeline pipeline = new ProcessingPipeline();
        pipeline.addProcessor(new AudioProcessor() {
            public void prepare(int r, long n) { throw new AssertionError("Approved PCM must not prepare mutable controls"); }
            public float[] process(float[] x, int c) { throw new AssertionError("Approved PCM must not process mutable controls"); }
            public boolean isEnabled() { return true; }
            public void setEnabled(boolean b) { }
        });
        Device device = new Device(); List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (AudioPlayer player = new AudioPlayer(pipeline, f -> device.line, Runnable::run, errors::add)) {
            WavFile source = file(.1f, 480001); player.prepare(source);
            float[] master = new float[source.getSamples().length]; Arrays.fill(master, .375f);
            assertTrue(player.publishRender(source.getSamples(), master));
            assertTrue(player.hasPublishedRender());
            player.seekTo(9.75); player.setOversampling(16); player.play();
            await(() -> device.closed);
            assertTrue(errors.isEmpty()); assertEquals((480001 - 468000) * 2, device.bytes.size());
            byte[] raw = device.bytes.toByteArray();
            for (int i = 0; i < raw.length; i += 2)
                assertEquals(.375, (short)((raw[i] & 255) | raw[i+1] << 8) / 32768.0, .00005);
        }
    }

    @Test void staleCompletedRenderCannotReplaceANewSourceWithSameLength() {
        try (AudioPlayer player = new AudioPlayer(new ProcessingPipeline(), f -> new Device().line, Runnable::run, e -> fail(e))) {
            WavFile old = file(.1f, 4096), replacement = file(.2f, 4096);
            player.prepare(old); assertTrue(player.publishRender(old.getSamples(), new float[4096]));
            player.prepare(replacement); assertFalse(player.hasPublishedRender());
            assertFalse(player.publishRender(old.getSamples(), new float[4096]));
            assertFalse(player.hasPublishedRender());
            assertThrows(IllegalArgumentException.class, () -> player.publishRender(replacement.getSamples(), new float[2]));
        }
    }

    @Test void newApprovedRenderCrossfadesWithoutMovingTheSourceClock() throws Exception {
        Device device = new Device(); device.block = true;
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (AudioPlayer player = new AudioPlayer(new ProcessingPipeline(), f -> device.line, Runnable::run, errors::add)) {
            WavFile source = file(.1f, 4097); player.prepare(source);
            float[] first = new float[4097], second = new float[4097];
            Arrays.fill(first, .25f); Arrays.fill(second, -.25f);
            player.publishRender(source.getSamples(), first); player.play();
            assertTrue(device.entered.await(3, TimeUnit.SECONDS));
            player.publishRender(source.getSamples(), second); device.block = false; device.release.countDown();
            await(() -> device.closed); assertEquals(4097 * 2, device.bytes.size()); assertTrue(errors.isEmpty());
            byte[] raw = device.bytes.toByteArray(); double previous = .25;
            for (int i = 0; i < raw.length; i += 2) {
                double sample = (short)((raw[i] & 255) | raw[i+1] << 8) / 32768.0;
                assertTrue(Math.abs(sample - previous) < .001, "A parameter update must not click"); previous = sample;
            }
            assertEquals(-.25, previous, .00005);
        }
    }

    @Test void approvedRenderLoopUsesOnlyTheSelectedPcmWithoutDsp() throws Exception {
        Device device = new Device(); device.block = true; device.blockAtWrite = 5;
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (AudioPlayer player = new AudioPlayer(new ProcessingPipeline(), f -> device.line, Runnable::run, errors::add)) {
            WavFile source = file(.1f, 10000); player.prepare(source);
            float[] master = new float[10000];
            for (int i = 0; i < master.length; i++) master[i] = (float)(.4 * Math.sin(i * .07));
            player.publishRender(source.getSamples(), master); player.setLoopRegion(8701, 9002);
            player.seekTo(8701.0 / 48000); player.play();
            await(() -> device.writes.get() >= 5); player.stop(); await(() -> device.closed);
            byte[] raw = device.bytes.toByteArray();
            // Stop may interrupt the fake's fifth write before its async close.
            assertTrue(raw.length == 4 * 301 * 2 || raw.length == 5 * 301 * 2);
            for (int i = 0; i < raw.length / 2; i++)
                assertEquals(master[8701 + i % 301], (short)((raw[i*2]&255)|raw[i*2+1]<<8) / 32768.0, .00005);
            assertTrue(errors.isEmpty());
        }
    }
    private static WavFile file(float value, int frames) {
        float[] pcm = new float[frames]; Arrays.fill(pcm, value);
        return new WavFile("generated", 48000, 1, pcm, 32, true);
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(2);
        assertTrue(condition.getAsBoolean(), "Timed out waiting for transport state");
    }
    private static final class Device {
        final AtomicInteger opens = new AtomicInteger(), writes = new AtomicInteger(), drains = new AtomicInteger(), flushes = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        volatile boolean block, closed, running, failOpen;
        volatile int maxWrite = Integer.MAX_VALUE, writeDelayMs, blockAtWrite = 1;
        volatile AudioFormat format;
        long frames;
        final SourceDataLine line = (SourceDataLine) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{SourceDataLine.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "open":
                            opens.incrementAndGet(); if (failOpen) throw new LineUnavailableException("fixture unavailable");
                            format = (AudioFormat) args[0]; return null;
                        case "start": running = true; return null;
                        case "stop": running = false; return null;
                        case "flush": flushes.incrementAndGet(); return null;
                        case "close": closed = true; running = false; release.countDown(); return null;
                        case "drain": drains.incrementAndGet(); return null;
                        case "write":
                            writes.incrementAndGet(); entered.countDown();
                            if (block && writes.get() >= blockAtWrite) try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                            if (closed) return 0;
                            if (writeDelayMs > 0) Thread.sleep(writeDelayMs);
                            int count = Math.min((int) args[2], maxWrite);
                            synchronized (bytes) { bytes.write((byte[]) args[0], (int) args[1], count); frames += count / format.getFrameSize(); }
                            return count;
                        case "getLongFramePosition": synchronized (bytes) { return frames; }
                        case "getFramePosition": return (int) frames;
                        case "isOpen": return !closed;
                        case "isActive": case "isRunning": return running;
                        case "getFormat": return format;
                        case "getControls": return new Control[0];
                        case "toString": return "OwnedFakeAudioDevice";
                        default: return method.getReturnType() == boolean.class ? false
                                : method.getReturnType() == int.class ? 0 : null;
                    }
                });
    }

    @Test void doublePlayIsSingleSessionAndStopDoesNotJoinOrDrain() throws Exception {
        Device device = new Device(); device.block = true;
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (AudioPlayer player = new AudioPlayer(new ProcessingPipeline(), f -> device.line, Runnable::run, errors::add)) {
            player.prepare(file(.25f, 48000)); player.play(); player.play();
            assertTrue(device.entered.await(3, TimeUnit.SECONDS)); assertEquals(1, device.opens.get());
            long start = System.nanoTime(); player.stop();
            assertTrue((System.nanoTime() - start) / 1e6 < 100, "Stop must never wait for audio under the control lock");
            assertEquals(AudioPlayer.State.STOPPED, player.getState()); await(() -> device.closed);
            assertEquals(0, device.drains.get()); assertTrue(errors.isEmpty());
        }
    }

    @Test void replacingSourceCannotLetOldCleanupCloseOrStopTheNewSession() throws Exception {
        Device first = new Device(), second = new Device(); first.block = true; second.block = true;
        AtomicInteger count = new AtomicInteger(); List<Runnable> ui = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (AudioPlayer player = new AudioPlayer(new ProcessingPipeline(), f -> count.getAndIncrement() == 0 ? first.line : second.line, ui::add, errors::add)) {
            player.prepare(file(.25f, 48000)); player.play(); assertTrue(first.entered.await(3, TimeUnit.SECONDS));
            player.prepare(file(-.5f, 96000)); player.play();
            assertTrue(second.entered.await(3, TimeUnit.SECONDS));
            for (Runnable callback : List.copyOf(ui)) callback.run();
            assertTrue(first.closed); assertFalse(second.closed); assertTrue(player.isPlaying());
            assertEquals(AudioPlayer.State.PLAYING, player.stateProperty().get()); assertTrue(errors.isEmpty());
        }
    }

    @Test void partialDeviceWritesDeliverEveryFrameAndNaturalEndDrains() throws Exception {
        Device device = new Device(); device.maxWrite = 32; List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (AudioPlayer player = new AudioPlayer(new ProcessingPipeline(), f -> device.line, Runnable::run, errors::add)) {
            player.prepare(file(.25f, 2057)); player.play(); await(() -> device.closed);
            assertEquals(2057 * 2, device.bytes.size()); assertEquals(1, device.drains.get());
            assertTrue(device.writes.get() > 3); assertTrue(errors.isEmpty());
            await(() -> player.getState() == AudioPlayer.State.STOPPED);
            assertEquals(0, player.getPositionSamples());
            byte[] output = device.bytes.toByteArray();
            for (int i = 0; i < output.length; i += 2) {
                int value = (short) ((output[i] & 255) | output[i + 1] << 8);
                assertTrue(Math.abs(value - 8192) <= 1);
            }
        }
    }

    @Test void unsupportedDeviceAndDspPreparationFailuresReleaseStateAndDevice() throws Exception {
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (AudioPlayer player = new AudioPlayer(new ProcessingPipeline(), f -> { throw new IllegalArgumentException("unsupported fixture"); }, Runnable::run, errors::add)) {
            player.prepare(file(.1f, 2048)); player.play(); await(() -> !player.isPlaying()); assertEquals(1, errors.size());
        }
        Device failed = new Device(); failed.failOpen = true;
        try (AudioPlayer player = new AudioPlayer(new ProcessingPipeline(), f -> failed.line, Runnable::run, errors::add)) {
            player.prepare(file(.1f, 2048)); player.play(); await(() -> failed.closed); await(() -> !player.isPlaying());
        }
        Device device = new Device(); ProcessingPipeline broken = new ProcessingPipeline();
        broken.addProcessor(new AudioProcessor() {
            public void prepare(int r, long n) { throw new IllegalStateException("synthetic DSP failure"); }
            public float[] process(float[] x, int ch) { return x; }
            public boolean isEnabled() { return true; }
            public void setEnabled(boolean b) { }
        });
        try (AudioPlayer player = new AudioPlayer(broken, f -> device.line, Runnable::run, errors::add)) {
            player.prepare(file(.1f, 2048)); player.play(); await(() -> device.closed); await(() -> !player.isPlaying());
            assertEquals(0, device.writes.get()); assertEquals(3, errors.size());
        }
    }

    @Test void pauseStopsDeviceAndResumeKeepsOneOwnedSession() throws Exception {
        Device device = new Device(); device.writeDelayMs = 2;
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (AudioPlayer player = new AudioPlayer(new ProcessingPipeline(), f -> device.line, Runnable::run, errors::add)) {
            player.prepare(file(.2f, 48000 * 5)); player.play(); assertTrue(device.entered.await(3, TimeUnit.SECONDS));
            player.pause(); await(() -> !device.running); int writes = device.writes.get();
            Thread.sleep(30); assertEquals(writes, device.writes.get()); assertEquals(AudioPlayer.State.PAUSED, player.getState());
            player.play(); await(() -> device.writes.get() > writes); assertEquals(1, device.opens.get()); assertTrue(errors.isEmpty());
        }
    }

    @Test void seekSurvivesAnInFlightWriteAndFlushesQueuedAudio() throws Exception {
        Device device = new Device(); device.block = true; device.writeDelayMs = 2;
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (AudioPlayer player = new AudioPlayer(new ProcessingPipeline(), f -> device.line, Runnable::run, errors::add)) {
            player.prepare(file(.2f, 48000 * 5)); player.play(); assertTrue(device.entered.await(3, TimeUnit.SECONDS));
            player.seekTo(2); device.block = false; device.release.countDown();
            await(() -> device.flushes.get() > 0); await(() -> player.getPositionSamples() >= 96000);
            assertTrue(player.getPositionSamples() < 144000); assertTrue(errors.isEmpty());
        }
    }

    @Test void bypassPlaysSourceButTapStillReceivesTheMaster() throws Exception {
        Device device = new Device(); List<float[]> tapped = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (AudioPlayer player = new AudioPlayer(new ProcessingPipeline(), f -> device.line, Runnable::run, errors::add)) {
            player.prepare(file(.1f, 1024)); float[] render = new float[1024]; Arrays.fill(render, .5f);
            player.setFixedRender(render); player.setMeterTap((block, ch) -> tapped.add(block.clone())); player.toggleAB(); player.play();
            await(() -> device.closed); assertEquals(1, tapped.size()); assertArrayEquals(render, tapped.get(0));
            byte[] raw = device.bytes.toByteArray(); int first = (short) ((raw[0] & 255) | raw[1] << 8);
            assertEquals(.1, first / 32768.0, .00005); assertTrue(errors.isEmpty());
        }
    }

    @Test void invalidSeekAndRenderAreRejectedBeforeChangingState() {
        try (AudioPlayer player = new AudioPlayer(new ProcessingPipeline(), f -> new Device().line, Runnable::run, e -> fail(e))) {
            player.prepare(file(.1f, 1024));
            assertThrows(IllegalArgumentException.class, () -> player.seekTo(Double.NaN));
            assertThrows(IllegalArgumentException.class, () -> player.setFixedRender(new float[7]));
            assertEquals(0, player.getPositionSamples()); assertFalse(player.isPlaying());
        }
    }

    @Test void oversampledPlaybackMatchesOfflineRenderIncludingPartialFinalBlocks() throws Exception {
        for (int factor : new int[]{1, 2, 4, 8, 16}) {
            float[] source = new float[8193];
            for (int i = 0; i < source.length; i++) source[i] = (float) (.7 * Math.sin(i * .37 * Math.PI));
            ProcessingPipeline offline = clippedChain(), live = clippedChain();
            WavFile audio = new WavFile("generated", 48000, 1, source, 32, true);
            offline.processOversampled(audio, factor, null);
            Device device = new Device(); List<Throwable> errors = new CopyOnWriteArrayList<>();
            try (AudioPlayer player = new AudioPlayer(live, f -> device.line, Runnable::run, errors::add)) {
                player.prepare(new WavFile("generated", 48000, 1, source, 32, true)); player.setOversampling(factor); player.play();
                await(() -> device.closed); assertTrue(errors.isEmpty());
                int latency = 0;
                if (factor > 1) {
                    var os = new com.dspark.core.OversamplingEngine();
                    os.prepare(factor, 1, 1024, com.dspark.core.OversamplingEngine.Quality.HIGH); latency = os.getLatencyBaseFrames();
                }
                byte[] raw = device.bytes.toByteArray(); assertEquals((source.length + latency) * 2, raw.length);
                double worst = 0;
                for (int i = 0; i < source.length; i++) {
                    int offset = (i + latency) * 2;
                    int pcm = (short) ((raw[offset] & 255) | raw[offset + 1] << 8);
                    worst = Math.max(worst, Math.abs(pcm / 32768.0 - audio.getSamples()[i]));
                }
                assertTrue(worst < .00006, "Preview/export discrepancy at " + factor + "x: " + worst);
            }
        }
    }

    private static ProcessingPipeline clippedChain() {
        ProcessingPipeline pipeline = new ProcessingPipeline();
        var clip = new com.quickmaster.processing.clip.SoftClipProcessor(); clip.setEnabled(true); clip.setSatDb(6);
        pipeline.addProcessor(clip); pipeline.addProcessor(new FadeProcessor(.02, .02)); pipeline.addProcessor(new PeakNormalizer(-1));
        return pipeline;
    }

    @Test void phaseEqualizerAndMultibandPreviewMatchLatencyAlignedExport() throws Exception {
        List<org.junit.jupiter.api.function.Executable> comparisons=new ArrayList<>();
        for (int factor : new int[]{1, 2, 4}) for (int ch : new int[]{1,2}) {
            int frames=8193; float[] source=new float[frames*ch];
            for(int f=0;f<frames;f++) for(int c=0;c<ch;c++) source[f*ch+c]=(float)(.3*Math.sin(f*.04)+.15*Math.cos(f*.73+c));
            source[source.length-1]=.85f;
            ProcessingPipeline offline=phaseChain(), live=phaseChain();
            WavFile audio=new WavFile("generated",48000,ch,source,32,true);
            offline.processOversampled(audio,factor,null);
            Device device=new Device(); List<Throwable> errors=new CopyOnWriteArrayList<>();
            try(AudioPlayer player=new AudioPlayer(live,f->device.line,Runnable::run,errors::add)) {
                player.prepare(new WavFile("generated",48000,ch,source,32,true)); player.setOversampling(factor); player.play();
                await(()->device.closed); assertTrue(errors.isEmpty(),errors.toString());
                int latency=(int)Math.round(live.getLatencyFrames()/(double)factor);
                if(factor>1) {
                    var os=new com.dspark.core.OversamplingEngine(); os.prepare(factor,ch,1024,com.dspark.core.OversamplingEngine.Quality.HIGH);
                    latency+=os.getLatencyBaseFrames();
                }
                byte[] raw=device.bytes.toByteArray(); assertEquals((frames+latency)*ch*2,raw.length);
                double worst=0; int worstIndex=0;
                for(int i=0;i<source.length;i++) {
                    int offset=(latency*ch+i)*2; int pcm=(short)((raw[offset]&255)|raw[offset+1]<<8);
                    double error=Math.abs(pcm/32768.0-audio.getSamples()[i]);
                    if(error>worst) { worst=error; worstIndex=i; }
                }
                double measured=worst;
                System.out.println("PHASE_PREVIEW factor="+factor+", ch="+ch+", worst="+worst+", frame="+worstIndex/ch);
                comparisons.add(()->assertTrue(measured<.00007,"Full preview/export factor="+factor+", ch="+ch+", worst="+measured));
            }
        }
        assertAll(comparisons);
    }

    private static ProcessingPipeline phaseChain() {
        var result=new ProcessingPipeline();
        var eq=new com.quickmaster.processing.eq.EqualizerProcessor();
        var band=new com.dspark.effects.MasterEqualizer.Band(); band.type=com.dspark.effects.MasterEqualizer.BandType.BELL;
        band.frequency=700; band.gainDb=4; band.phase=com.dspark.effects.MasterEqualizer.BandPhase.LINEAR;
        eq.setBand(0,band); result.addProcessor(eq);
        var limiter=new com.quickmaster.processing.limit.MultibandLimiterProcessor(); limiter.setEnabled(true);
        for(int i=0;i<4;i++) limiter.setPushDb(i,3);
        result.addProcessor(limiter); result.addProcessor(new FadeProcessor(.02,.02)); result.addProcessor(new PeakNormalizer(-1));
        return result;
    }

    @Test void seekingIntoStatefulDspStartsAtTheExactExportSampleWithoutColdFilterHistory() throws Exception {
        for(int factor:new int[]{1,2,4}) {
            int frames=18007, seek=7003; float[] source=new float[frames];
            for(int i=0;i<frames;i++) source[i]=(float)(.35*Math.sin(i*.053)+.2*Math.cos(i*.0037));
            var offline=phaseChain(); var audio=new WavFile("generated",48000,1,source,32,true);
            offline.processOversampled(audio,factor,null);
            Device device=new Device(); List<Throwable> errors=new CopyOnWriteArrayList<>();
            try(var player=new AudioPlayer(phaseChain(),f->device.line,Runnable::run,errors::add)) {
                player.prepare(new WavFile("generated",48000,1,source,32,true)); player.setOversampling(factor);
                player.seekTo(seek/48000.0); player.play(); await(()->device.closed);
                assertTrue(errors.isEmpty(),errors.toString()); byte[] raw=device.bytes.toByteArray();
                assertEquals((frames-seek)*2,raw.length,"No cold-filter warm-up may reach the device after seek.");
                for(int i=seek;i<frames;i++) {
                    int offset=(i-seek)*2; int pcm=(short)((raw[offset]&255)|raw[offset+1]<<8);
                    assertEquals(audio.getSamples()[i],pcm/32768.0,.00007,"seek="+seek+", factor="+factor+", frame="+i);
                }
            }
        }
    }

    @Test void repeatedLoopUsesSourceContextAndDoesNotLeakThePreviousIterationsFilterState() throws Exception {
        int frames=9001,start=2503,end=4107; float[] source=new float[frames];
        for(int i=0;i<frames;i++) source[i]=(float)(.4*Math.sin(i*.037));
        var audio=new WavFile("generated",48000,1,source,32,true); phaseChain().process(audio);
        Device device=new Device(); device.writeDelayMs=2; List<Throwable> errors=new CopyOnWriteArrayList<>();
        try(var player=new AudioPlayer(phaseChain(),f->device.line,Runnable::run,errors::add)) {
            player.prepare(new WavFile("generated",48000,1,source,32,true)); player.setLoopRegion(start,end);
            player.seekTo(start/48000.0); player.play();
            await(()->{synchronized(device.bytes){return device.bytes.size()>=(end-start)*2*3;}});
            assertEquals(1,device.flushes.get(),"Loop wrap must preserve the preceding tail still queued at the device.");
            player.stop(); await(()->device.closed); assertTrue(errors.isEmpty(),errors.toString());
            byte[] raw=device.bytes.toByteArray();
            for(int i=0;i<(end-start)*2;i++) {
                int offset=i*2; int pcm=(short)((raw[offset]&255)|raw[offset+1]<<8);
                assertEquals(audio.getSamples()[start+i%(end-start)],pcm/32768.0,.00007,"loop frame="+i);
            }
        }
    }

    @Test void parkingLivePlaybackOnAnAlignedAbRenderDoesNotJumpAheadByDspLatency() throws Exception {
        int frames=18007; float[] source=new float[frames];
        for(int i=0;i<frames;i++) source[i]=(float)(.4*Math.sin(i*.037));
        var audio=new WavFile("generated",48000,1,source,32,true); phaseChain().process(audio);
        Device device=new Device(); device.block=true; device.blockAtWrite=5;
        List<Throwable> errors=new CopyOnWriteArrayList<>(); var live=phaseChain();
        try(var player=new AudioPlayer(live,f->device.line,Runnable::run,errors::add)) {
            player.prepare(new WavFile("generated",48000,1,source,32,true)); player.play(); await(()->device.writes.get()>=5);
            int latency=live.getLatencyFrames();
            player.setFixedRender(audio.getSamples()); device.block=false; device.release.countDown();
            await(()->device.closed); assertTrue(errors.isEmpty(),errors.toString());
            byte[] raw=device.bytes.toByteArray(); assertEquals((frames+latency)*2,raw.length);
            for(int i=0;i<frames;i++) {
                int offset=(i+latency)*2; int pcm=(short)((raw[offset]&255)|raw[offset+1]<<8);
                assertEquals(audio.getSamples()[i],pcm/32768.0,.00007,"A/B frame="+i);
            }
        }
    }

    @Test void returningFromAbRenderToLiveDspRebuildsHistoryWithoutRepeatingOrSkippingAudio() throws Exception {
        int frames=18007; float[] source=new float[frames];
        for(int i=0;i<frames;i++) source[i]=(float)(.4*Math.sin(i*.037));
        var audio=new WavFile("generated",48000,1,source,32,true); phaseChain().process(audio);
        Device device=new Device(); device.block=true; device.blockAtWrite=5;
        List<Throwable> errors=new CopyOnWriteArrayList<>();
        try(var player=new AudioPlayer(phaseChain(),f->device.line,Runnable::run,errors::add)) {
            player.prepare(new WavFile("generated",48000,1,source,32,true));
            player.setFixedRender(audio.getSamples()); player.play(); await(()->device.writes.get()>=5);
            player.setFixedRender(null); device.block=false; device.release.countDown();
            await(()->device.closed); assertTrue(errors.isEmpty(),errors.toString());
            byte[] raw=device.bytes.toByteArray(); assertEquals(frames*2,raw.length);
            for(int i=0;i<frames;i++) {
                int offset=i*2; int pcm=(short)((raw[offset]&255)|raw[offset+1]<<8);
                assertEquals(audio.getSamples()[i],pcm/32768.0,.00007,"A/B return frame="+i);
            }
        }
    }
}
