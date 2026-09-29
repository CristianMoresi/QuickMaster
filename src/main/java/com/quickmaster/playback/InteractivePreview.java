package com.quickmaster.playback;

import com.quickmaster.processing.PreviewWindowRenderer;
import com.quickmaster.processing.dynamics.leveler.CancellationToken;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One bounded latest-request worker, independent of full-file analysis and playback. */
public final class InteractivePreview implements AutoCloseable {
    private final AudioPlayer player;
    private final Consumer<Throwable> errors;
    private final Runnable ready;
    private final ScheduledExecutorService worker;
    private final java.util.concurrent.atomic.AtomicBoolean scheduled = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile Request current;
    private volatile Request working;
    private long sequence;
    private boolean closed;
    private static final class Request {
        final float[] source; final long generation; final int rate, channels;
        final Supplier<PreviewWindowRenderer> factory;
        final CancellationToken cancellation = new CancellationToken();
        PreviewWindowRenderer renderer; PreviewWindow window, preceding;
        long renderNanos;
        Request(float[] source, long generation, int rate, int channels, Supplier<PreviewWindowRenderer> factory) {
            this.source = source; this.generation = generation; this.rate = rate;
            this.channels = channels; this.factory = factory;
        }
    }
    public InteractivePreview(AudioPlayer player, Consumer<Throwable> errors) {
        this(player, errors, () -> {});
    }
    public InteractivePreview(AudioPlayer player, Consumer<Throwable> errors, Runnable ready) {
        this.player = player; this.errors = errors; this.ready = ready;
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "QuickMaster-InteractivePreview"); t.setDaemon(true); return t;
        });
    }
    public synchronized void request(float[] source, int rate, int channels, Supplier<PreviewWindowRenderer> factory) {
        if (closed) return;
        // Coalesce queued edits without starving the in-flight short window.
        // Publications are monotonic; a newer audible window cannot be replaced
        // by an older one. Final/source/A-B barriers cancel even in-flight work.
        Request next = new Request(source, ++sequence, rate, channels, factory);
        if (!player.beginPreview(source, next.generation)) { current = null; return; }
        current = next;
        wake(0);
    }
    public synchronized void cancel() {
        if (current != null) current.cancellation.cancel();
        if (working != null) working.cancellation.cancel();
        current = null; player.clearPreview();
    }
    private void tick() {
        Request r = current;
        try {
            if (r == null) return;
            working = r;
            long cursor = player.getPreviewRequestFrame();
            int total = r.source.length / r.channels;
            if (cursor >= total) return;
            if (r.window != null && cursor < r.window.startFrame() && r.preceding != null && r.preceding.covers(cursor, 1)) return;
            long loopStart = player.getPreviewLoopStart(), loopEnd = player.getPreviewLoopEnd();
            int ahead = (int)Math.min(total - cursor, Math.max(r.rate / 6, r.rate * (r.renderNanos / 1e9 + .15)));
            if (loopStart >= 0 && cursor >= loopStart && cursor < loopEnd) ahead = (int)Math.min(ahead, loopEnd - cursor);
            if (r.window != null && r.window.covers(cursor, ahead)) return;
            if (r.renderer == null) r.renderer = r.factory.get();
            int start = Math.max(0, (int)cursor - AudioPlayer.BUFFER_FRAMES);
            // Prepare the next overlapping window IN ADVANCE, retaining the
            // preceding one for playback. Re-rendering from the moving cursor
            // wastes the very lookahead a heavier oversampled chain needs.
            if (r.window != null && r.window.covers(cursor, 1)) start = Math.max(start, (int)r.window.endFrame() - r.rate / 10);
            int frames = Math.min(r.rate / 2, total - start);
            if (loopStart >= 0 && loopEnd - loopStart <= 2L * r.rate && cursor >= loopStart && cursor < loopEnd) {
                start = Math.toIntExact(loopStart); frames = Math.toIntExact(loopEnd - loopStart);
            }
            long began = System.nanoTime();
            float[] pcm = r.renderer.render(start, frames, r.cancellation);
            r.renderNanos = System.nanoTime() - began;
            PreviewWindow window = new PreviewWindow(r.source, r.generation, start, r.channels, pcm,
                    r.renderer.getEqGainDb(), r.renderer.getOutputGainDb());
            synchronized (this) {
                if (current != null && current.source == r.source && !r.cancellation.isCancelled() && player.publishPreview(window)) {
                    r.preceding = r.window; r.window = window; ready.run();
                }
            }
        } catch (CancellationException ignored) {
        } catch (Throwable error) {
            synchronized (this) {
                if (current != r) return;
                current = null; player.clearPreview();
            }
            errors.accept(error);
        } finally {
            working = null;
            scheduled.set(false);
            if (current != null) wake(3);
        }
    }
    private void wake(int delayMs) {
        if (!scheduled.compareAndSet(false, true)) return;
        try { worker.schedule(this::tick, delayMs, TimeUnit.MILLISECONDS); }
        catch (RejectedExecutionException ignored) { scheduled.set(false); }
    }
    @Override public synchronized void close() { closed = true; cancel(); worker.shutdownNow(); }
}
