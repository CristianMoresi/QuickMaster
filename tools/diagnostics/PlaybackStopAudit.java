import com.quickmaster.playback.AudioPlayer;
import com.quickmaster.processing.ProcessingPipeline;
import javafx.application.Platform;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Reproduces the stop/join monitor contention without opening an audio device. */
public class PlaybackStopAudit {
    public static void main(String[] args) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        Throwable[] failure = {null};
        Platform.startup(() -> {
            try {
                AudioPlayer player = new AudioPlayer(new ProcessingPipeline());
                Field field = AudioPlayer.class.getDeclaredField("playbackThread");
                field.setAccessible(true);
                CountDownLatch entered = new CountDownLatch(1);
                Thread worker = new Thread(() -> {
                    entered.countDown();
                    synchronized (player) { /* same monitor as a real buffer's position update */ }
                }, "audit-owned-audio-worker");
                synchronized (player) {
                    field.set(player, worker);
                    worker.start();
                    if (!entered.await(2, TimeUnit.SECONDS)) throw new AssertionError("worker did not start");
                    long started = System.nanoTime();
                    player.stop();
                    double ms = (System.nanoTime() - started) / 1e6;
                    System.out.printf(java.util.Locale.ROOT,
                            "STOP_AUDIT fxBlockedMs=%.3f workerStillAlive=%s trackedWorker=%s%n",
                            ms, worker.isAlive(), field.get(player));
                    if (ms > 100) failure[0] = new AssertionError("stop blocks FX while owning the monitor the worker needs");
                }
                worker.join(2000);
            } catch (Throwable e) { failure[0] = e; }
            finally { finished.countDown(); }
        });
        if (!finished.await(10, TimeUnit.SECONDS)) throw new AssertionError("audit timeout");
        Platform.exit();
        if (failure[0] != null) { failure[0].printStackTrace(); System.exit(1); }
    }
}
