package com.quickmaster.playback;

import com.quickmaster.audio.WavFile;
import com.quickmaster.processing.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class InteractivePreviewTest {
    @Test void shortLoopIsOneCompleteReusableWindow() throws Exception {
        float[] source=new float[96000];var errors=new CopyOnWriteArrayList<Throwable>();
        try(var player=new AudioPlayer(new ProcessingPipeline(),f->{throw new AssertionError();},Runnable::run,errors::add);
            var preview=new InteractivePreview(player,errors::add)) {
            player.prepare(new WavFile("loop",48000,1,source,32,true));player.publishRender(source,source.clone());
            player.setLoopRegion(1133,1459);player.seekTo(1400.0/48000);
            preview.request(source,48000,1,()->renderer(source));await(()->player.getPreviewWindow()!=null);
            var window=player.getPreviewWindow();assertEquals(1133,window.startFrame());assertEquals(1459,window.endFrame());
            Thread.sleep(30);assertSame(window,player.getPreviewWindow());assertTrue(errors.isEmpty());
        }
    }
    static void await(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(2);
        assertTrue(condition.getAsBoolean());
    }
    static PreviewWindowRenderer renderer(float[] source) {
        return new PreviewWindowRenderer(new ProcessingPipeline(),source,48000,1,1);
    }
    @Test void pendingEditsCoalesceButNeverPublishAfterFinalBarrier() throws Exception {
        float[] source=new float[48000*3];
        var errors=new CopyOnWriteArrayList<Throwable>();
        CountDownLatch started=new CountDownLatch(1), release=new CountDownLatch(1);
        try(var player=new AudioPlayer(new ProcessingPipeline(),f->{throw new AssertionError("No device needed");},Runnable::run,errors::add);
            var preview=new InteractivePreview(player,errors::add)) {
            player.prepare(new WavFile("fixture",48000,1,source,32,true));player.publishRender(source,source.clone());
            AtomicInteger builds=new AtomicInteger();
            preview.request(source,48000,1,()->{builds.incrementAndGet();started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}return renderer(source);});
            assertTrue(started.await(2,TimeUnit.SECONDS));
            for(int i=0;i<20;i++)preview.request(source,48000,1,()->{builds.incrementAndGet();return renderer(source);});
            release.countDown();await(()->player.getPreviewWindow()!=null&&player.getPreviewWindow().generation()==21);
            assertEquals(2,builds.get(),"Only active and latest requests may build DSP");
            preview.cancel();player.publishRender(source,source.clone());Thread.sleep(30);assertNull(player.getPreviewWindow());
            assertTrue(errors.isEmpty());
        } finally { release.countDown(); }
    }
    @Test void sourceReplacementAndCancellationRejectOwnedOldWork() throws Exception {
        float[] source=new float[96000];var errors=new CopyOnWriteArrayList<Throwable>();
        CountDownLatch started=new CountDownLatch(1), release=new CountDownLatch(1);
        try(var player=new AudioPlayer(new ProcessingPipeline(),f->{throw new AssertionError();},Runnable::run,errors::add);
            var preview=new InteractivePreview(player,errors::add)) {
            player.prepare(new WavFile("first",48000,1,source,32,true));player.publishRender(source,source.clone());
            preview.request(source,48000,1,()->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}return renderer(source);});
            assertTrue(started.await(2,TimeUnit.SECONDS));
            preview.cancel();player.prepare(new WavFile("second",48000,1,new float[96000],32,true));
            release.countDown();Thread.sleep(50);assertNull(player.getPreviewWindow());assertTrue(errors.isEmpty());
        } finally {release.countDown();}
    }
}
